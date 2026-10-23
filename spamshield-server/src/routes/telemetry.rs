use crate::db::AppDatabase;
use crate::models::{
    BatchTelemetryResponse, CallTelemetryDoc, FeedbackPatchRequest, FeedbackPatchResponse,
    UserFeedback,
};
use crate::services::recalibrator;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::{Duration, Utc};
use futures::{future::join_all, TryStreamExt};
use mongodb::options::{FindOptions, InsertManyOptions, UpdateOptions};
use std::collections::{HashMap, HashSet};
use tracing::{error, info};

struct PrefixBatchAggregation {
    ddd: String,
    carrier_guess: String,
    reason: String,
    hit_delta: i32,
    numbers: HashSet<String>,
}

/// POST /api/v1/telemetry/batch
/// Idempotent batch ingestion:
/// - Identifies which `_id`s are new vs already stored in `call_telemetry`.
/// - Inserts only new documents and increments `prefix_quarantine.hit_count` strictly once per new blocked call.
/// - Updates `user_feedback` and `call_duration_sec` on already-existing documents if modified offline.
pub async fn post_telemetry_batch(
    State(db): State<AppDatabase>,
    Json(mut batch): Json<Vec<CallTelemetryDoc>>,
) -> Result<Json<BatchTelemetryResponse>, (StatusCode, String)> {
    if batch.is_empty() {
        return Ok(Json(BatchTelemetryResponse {
            accepted_ids: vec![],
            quarantine_updates: 0,
        }));
    }

    let accepted_ids: Vec<String> = batch.iter().map(|d| d.id.clone()).collect();
    let now_utc = Utc::now();
    let now_bson = BsonDateTime::from_chrono(now_utc);
    let telemetry_coll = db.call_telemetry();

    // Find which IDs already exist to guarantee strict idempotency on retries
    let existing_cursor = telemetry_coll
        .find(doc! { "_id": { "$in": &accepted_ids } })
        .with_options(
            FindOptions::builder()
                .projection(doc! { "_id": 1 })
                .build(),
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let existing_docs: Vec<Document> = existing_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let existing_ids: HashSet<String> = existing_docs
        .iter()
        .filter_map(|d| d.get_str("_id").ok().map(|s| s.to_string()))
        .collect();

    let mut new_calls: Vec<&mut CallTelemetryDoc> = Vec::new();
    let mut updated_existing_calls: Vec<&CallTelemetryDoc> = Vec::new();

    for item in batch.iter_mut() {
        item.audit.synced_at = now_utc;
        if existing_ids.contains(&item.id) {
            if item.audit.user_feedback != UserFeedback::UNRATED
                || item.decision_vector.call_duration_sec.is_some()
            {
                updated_existing_calls.push(item);
            }
        } else {
            new_calls.push(item);
        }
    }

    // 1. Insert genuinely new telemetry documents
    if !new_calls.is_empty() {
        let bson_docs: Vec<Document> = new_calls.iter().map(|c| c.to_bson_document()).collect();
        let insert_opts = InsertManyOptions::builder().ordered(false).build();
        if let Err(err) = telemetry_coll
            .insert_many(bson_docs)
            .with_options(insert_opts)
            .await
        {
            let err_str = err.to_string();
            if !err_str.contains("11000") {
                error!("Error during telemetry batch insert: {}", err);
                return Err((
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("Database insert error: {}", err),
                ));
            }
        }
        info!("Inserted {} new telemetry records", new_calls.len());
    }

    // 2. Sync offline updates (feedback / ping call duration) for existing documents concurrently
    if !updated_existing_calls.is_empty() {
        let update_futs = updated_existing_calls.iter().map(|call| {
            let coll = telemetry_coll.clone();
            let id = call.id.clone();
            let fb = call.audit.user_feedback.as_str().to_string();
            let cat = call.decision_vector.category.as_str().to_string();
            let outcome = call.decision_vector.outcome.as_str().to_string();
            let score = call.decision_vector.final_score;
            let dur = call
                .decision_vector
                .call_duration_sec
                .map(Bson::Int32)
                .unwrap_or(Bson::Null);
            async move {
                let _ = coll
                    .update_one(
                        doc! { "_id": id },
                        doc! {
                            "$set": {
                                "audit.user_feedback": fb,
                                "audit.synced_at": now_bson,
                                "decision_vector.outcome": outcome,
                                "decision_vector.final_score": score,
                                "decision_vector.category": cat,
                                "decision_vector.call_duration_sec": dur,
                            }
                        },
                    )
                    .await;
            }
        });
        join_all(update_futs).await;
    }

    // 3. Group NEW blocked / shadow-blocked calls by prefix_7 and upsert prefix_quarantine concurrently
    let mut grouped_prefixes: HashMap<String, PrefixBatchAggregation> = HashMap::new();
    for call in &new_calls {
        if call.decision_vector.outcome.is_blocked_or_shadow()
            && !call.call_details.prefix_7.is_empty()
        {
            let entry = grouped_prefixes
                .entry(call.call_details.prefix_7.clone())
                .or_insert_with(|| PrefixBatchAggregation {
                    ddd: call.call_details.ddd.clone(),
                    carrier_guess: call
                        .call_details
                        .carrier_guess
                        .clone()
                        .unwrap_or_else(|| "Outros".to_string()),
                    reason: call.decision_vector.category.as_str().to_string(),
                    hit_delta: 0,
                    numbers: HashSet::new(),
                });
            entry.hit_delta += 1;
            if !call.call_details.normalized_number.is_empty() {
                entry
                    .numbers
                    .insert(call.call_details.normalized_number.clone());
            }
        }
    }

    let quarantine_coll = db.prefix_quarantine();
    let update_futures = grouped_prefixes.into_iter().map(|(prefix_7, agg)| {
        let coll = quarantine_coll.clone();
        async move {
            let ttl_days = if agg.hit_delta > 5 { 30 } else { 7 };
            let expires_at = BsonDateTime::from_chrono(now_utc + Duration::days(ttl_days));
            let distinct_vec: Vec<String> = agg.numbers.into_iter().collect();

            let update_doc = doc! {
                "$inc": { "hit_count": agg.hit_delta },
                "$addToSet": { "distinct_numbers_seen": { "$each": distinct_vec } },
                "$set": {
                    "last_seen_at": now_bson,
                    "expires_at": expires_at,
                },
                "$setOnInsert": {
                    "ddd": agg.ddd,
                    "carrier_guess": agg.carrier_guess,
                    "penalty_weight": 65_i32,
                    "reason": agg.reason,
                    "first_seen_at": now_bson,
                }
            };

            coll.update_one(doc! { "prefix_7": prefix_7 }, update_doc)
                .with_options(UpdateOptions::builder().upsert(true).build())
                .await
                .is_ok()
        }
    });

    let results = join_all(update_futures).await;
    let quarantine_updates = results.into_iter().filter(|ok| *ok).count();

    Ok(Json(BatchTelemetryResponse {
        accepted_ids,
        quarantine_updates,
    }))
}

/// PATCH /api/v1/telemetry/:id/feedback
pub async fn patch_telemetry_feedback(
    State(db): State<AppDatabase>,
    Path(id): Path<String>,
    Json(payload): Json<FeedbackPatchRequest>,
) -> Result<Json<FeedbackPatchResponse>, (StatusCode, String)> {
    let telemetry_coll = db.call_telemetry();

    let existing_doc = telemetry_coll
        .find_one(doc! { "_id": &id })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let mut quarantine_removed_prefix = None;
    let mut recalibrated_rules = Vec::new();

    if let Some(ref doc_val) = existing_doc {
        let mut set_doc = doc! {
            "audit.user_feedback": payload.user_feedback.as_str(),
            "audit.synced_at": BsonDateTime::now(),
        };
        match payload.user_feedback {
            UserFeedback::CONFIRMED_SPAM => {
                set_doc.insert("decision_vector.outcome", "BLOCKED");
                set_doc.insert("decision_vector.category", "ROBOCALL_PREFIX_CLUSTER");
                set_doc.insert("decision_vector.final_score", 100_i32);
            }
            UserFeedback::FALSE_POSITIVE => {
                set_doc.insert("decision_vector.outcome", "ALLOWED_CONTACT");
                set_doc.insert("decision_vector.category", "LEGITIMATE");
                set_doc.insert("decision_vector.final_score", 0_i32);
            }
            UserFeedback::UNRATED => {}
        }

        telemetry_coll
            .update_one(doc! { "_id": &id }, doc! { "$set": set_doc })
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

        match payload.user_feedback {
            UserFeedback::FALSE_POSITIVE => {
                let (removed, rules) = recalibrator::handle_false_positive(&db, doc_val)
                    .await
                    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
                quarantine_removed_prefix = removed;
                recalibrated_rules = rules;
            }
            UserFeedback::CONFIRMED_SPAM => {
                let _ = recalibrator::handle_confirmed_spam(&db, doc_val)
                    .await
                    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            }
            UserFeedback::UNRATED => {}
        }
    } else {
        return Err((
            StatusCode::NOT_FOUND,
            format!("Telemetry record '{}' not found", id),
        ));
    }

    Ok(Json(FeedbackPatchResponse {
        id,
        user_feedback: payload.user_feedback,
        quarantine_removed_prefix,
        recalibrated_rules,
    }))
}
