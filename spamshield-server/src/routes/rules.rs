use super::AppState;
use crate::db::AppDatabase;
use crate::db::{parse_active_rule, parse_anatel_doc, parse_prefix_quarantine};
use crate::models::{
    ActiveRuleDoc, AnatelNumberingPlanDoc, PrefixQuarantineDoc, SyncPayloadQuery,
    SyncPayloadResponse,
};
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::{TimeZone, Utc};
use futures::TryStreamExt;
use mongodb::options::{FindOptions, UpdateOptions};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// GET /api/v1/sync/payload?ddd=31&since_timestamp=1700000000
/// Returns active rules (`enabled: true`), active `prefix_quarantine`, and `anatel_numbering_plan`.
pub async fn get_sync_payload(
    State(state): State<AppState>,
    Query(query): Query<SyncPayloadQuery>,
) -> Result<Json<SyncPayloadResponse>, (StatusCode, String)> {
    let db = &state.db;
    // 1. Active rules (enabled: true) sorted by priority ascending
    let rules_cursor = db
        .active_rules()
        .find(doc! {})
        .with_options(FindOptions::builder().sort(doc! { "priority": 1 }).build())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let rules_docs: Vec<Document> = rules_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let active_rules: Vec<ActiveRuleDoc> =
        rules_docs.iter().filter_map(parse_active_rule).collect();

    // 2. Active prefix_quarantine (expires_at > now)
    let now_bson = BsonDateTime::now();
    let quarantine_cursor = db
        .prefix_quarantine()
        .find(doc! { "expires_at": { "$gt": now_bson }, "deleted": { "$ne": true } })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let quarantine_docs: Vec<Document> = quarantine_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let prefix_quarantine: Vec<PrefixQuarantineDoc> = quarantine_docs
        .iter()
        .filter_map(parse_prefix_quarantine)
        .collect();

    let deleted_docs: Vec<Document> = db
        .prefix_quarantine()
        .find(doc! { "deleted": true })
        .projection(doc! { "prefix_7": 1 })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let quarantine_deleted_prefixes = deleted_docs
        .iter()
        .filter_map(|d| d.get_str("prefix_7").ok().map(str::to_string))
        .collect();

    // 3. Anatel numbering plan: return user's DDD + all corporate PABX/VoIP ranges across Brazil
    let anatel_filter = if let Some(ref ddd) = query.ddd {
        let trimmed = ddd.trim();
        if !trimmed.is_empty() {
            doc! {
                "$or": [
                    { "ddd": trimmed },
                    { "is_corporate_pabx_range": true },
                    { "ddd": "11" },
                    { "ddd": "21" }
                ]
            }
        } else {
            Document::new()
        }
    } else {
        Document::new()
    };

    let anatel_cursor = db
        .anatel_numbering_plan()
        .find(anatel_filter)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let anatel_docs: Vec<Document> = anatel_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let anatel_numbering_plan: Vec<AnatelNumberingPlanDoc> =
        anatel_docs.iter().filter_map(parse_anatel_doc).collect();

    Ok(Json(SyncPayloadResponse {
        quarantine_snapshot_complete: true,
        quarantine_deleted_prefixes,
        active_rules,
        prefix_quarantine,
        anatel_numbering_plan,
        server_timestamp: Utc::now().timestamp_millis(),
    }))
}

/// POST /api/v1/rules
pub async fn upsert_active_rule(
    State(state): State<AppState>,
    Json(rule): Json<ActiveRuleDoc>,
) -> Result<Json<ActiveRuleDoc>, (StatusCode, String)> {
    let db = &state.db;
    let pattern_bson = match &rule.pattern {
        Some(p) => Bson::String(p.clone()),
        None => Bson::Null,
    };

    db.active_rules()
        .update_one(
            doc! { "rule_id": &rule.rule_id },
            doc! {
                "$set": {
                    "name": &rule.name,
                    "rule_type": &rule.rule_type,
                    "pattern": pattern_bson,
                    "weight": rule.weight,
                    "priority": rule.priority,
                    "enabled": rule.enabled,
                    "updated_at": BsonDateTime::now(),
                }
            },
        )
        .with_options(UpdateOptions::builder().upsert(true).build())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(rule))
}

#[derive(Debug, Default, Deserialize)]
pub struct MutationQuery {
    pub recorded_at: Option<i64>,
    #[serde(default)]
    pub automatic: bool,
}

#[derive(Debug, Serialize)]
pub struct QuarantineUpsertResponse {
    #[serde(flatten)]
    pub quarantine: PrefixQuarantineDoc,
    pub applied: bool,
}

pub(crate) fn duplicate_key(error: &mongodb::error::Error) -> bool {
    match error.kind.as_ref() {
        mongodb::error::ErrorKind::Write(mongodb::error::WriteFailure::WriteError(e)) => {
            e.code == 11000
        }
        mongodb::error::ErrorKind::Command(e) => e.code == 11000,
        _ => false,
    }
}

/// Timestamp filter and deletion live in the same document, so stale uploads cannot resurrect it.
pub(crate) fn quarantine_revision_filter(prefix: &str, recorded_at: i64) -> Document {
    doc! { "prefix_7": prefix, "$or": [
        { "mutation_at": { "$lt": recorded_at } },
        { "deleted": { "$ne": true }, "mutation_at": { "$lte": recorded_at } },
        { "mutation_at": { "$exists": false } }
    ] }
}

pub async fn store_quarantine(
    db: &AppDatabase,
    q: &PrefixQuarantineDoc,
    recorded_at: i64,
) -> Result<(), mongodb::error::Error> {
    store_quarantine_mutation(db, q, recorded_at, false)
        .await
        .map(|_| ())
}

async fn store_quarantine_mutation(
    db: &AppDatabase,
    q: &PrefixQuarantineDoc,
    recorded_at: i64,
    automatic: bool,
) -> Result<bool, mongodb::error::Error> {
    let now = Utc::now();
    if q.expires_at <= now || q.prefix_7.is_empty() {
        return Ok(false);
    }
    let collection = db.prefix_quarantine();
    let now_bson = BsonDateTime::from_chrono(now);
    let mut filter = quarantine_revision_filter(&q.prefix_7, recorded_at);
    if automatic {
        if q.prefix_7.len() == 8
            && q.prefix_7.bytes().all(|b| b.is_ascii_digit())
            && collection
                .find_one(doc! {
                    "prefix_7": &q.prefix_7[..7],
                    "deleted": { "$ne": true },
                    "expires_at": { "$gt": now_bson }
                })
                .await?
                .is_some()
        {
            return Ok(false);
        }
        // Compare the expiry during the upsert so an exact-prefix mutation made by another
        // client after the original call is not overwritten by a queued automatic observation.
        filter.insert(
            "$and",
            vec![doc! { "$or": [
                { "deleted": true },
                { "expires_at": { "$lte": now_bson } },
                { "expires_at": { "$exists": false } }
            ] }],
        );
    }
    let expiry = BsonDateTime::from_chrono(q.expires_at);
    let first = BsonDateTime::from_chrono(q.first_seen_at);
    let last = BsonDateTime::from_chrono(q.last_seen_at);
    let update = vec![doc! { "$set": {
        "prefix_7": { "$literal": &q.prefix_7 },
        "ddd": { "$literal": &q.ddd }, "carrier_guess": { "$literal": bson::to_bson(&q.carrier_guess).unwrap_or(Bson::Null) },
        "reason": { "$literal": &q.reason }, "penalty_weight": q.penalty_weight,
        "first_seen_at": { "$ifNull": ["$first_seen_at", first] },
        "last_seen_at": { "$max": [{ "$ifNull": ["$last_seen_at", last] }, last] },
        "expires_at": { "$cond": [{ "$eq": ["$deleted", true] }, expiry,
            { "$max": [{ "$ifNull": ["$expires_at", expiry] }, expiry] }] },
        "hit_count": { "$max": [{ "$ifNull": ["$hit_count", 0] }, q.hit_count] },
        "distinct_numbers_seen": { "$setUnion": [{ "$ifNull": ["$distinct_numbers_seen", []] }, { "$literal": &q.distinct_numbers_seen }] },
        "deleted": false, "mutation_at": recorded_at
    } }];
    match collection.update_one(filter, update).upsert(true).await {
        Ok(result) => Ok(result.modified_count > 0 || result.upserted_id.is_some()),
        Err(error) if duplicate_key(&error) => Ok(false),
        Err(error) => Err(error),
    }
}

pub async fn upsert_quarantine(
    State(state): State<AppState>,
    Query(query): Query<MutationQuery>,
    Json(q): Json<PrefixQuarantineDoc>,
) -> Result<Json<QuarantineUpsertResponse>, (StatusCode, String)> {
    let applied = store_quarantine_mutation(
        &state.db,
        &q,
        query
            .recorded_at
            .unwrap_or(q.last_seen_at.timestamp_millis()),
        query.automatic,
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(QuarantineUpsertResponse {
        quarantine: q,
        applied,
    }))
}

pub async fn upsert_quarantine_batch(
    State(state): State<AppState>,
    Json(items): Json<Vec<PrefixQuarantineDoc>>,
) -> Result<Json<Value>, (StatusCode, String)> {
    for q in &items {
        store_quarantine(&state.db, q, q.last_seen_at.timestamp_millis())
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(Json(
        json!({ "upserted": items.len(), "received": items.len() }),
    ))
}

pub async fn remove_quarantine(
    db: &AppDatabase,
    prefix: &str,
    recorded_at: i64,
) -> Result<(), mongodb::error::Error> {
    if prefix.is_empty() {
        return Ok(());
    }
    let at = BsonDateTime::from_chrono(
        Utc.timestamp_millis_opt(recorded_at)
            .single()
            .unwrap_or_else(Utc::now),
    );
    // Keep deletion knowledge through normal Mongo TTL cleanup. It is omitted from active snapshots.
    let retained = BsonDateTime::from_chrono(Utc.with_ymd_and_hms(2100, 1, 1, 0, 0, 0).unwrap());
    let filter = doc! { "prefix_7": prefix, "$or": [{ "mutation_at": { "$lte": recorded_at } }, { "mutation_at": { "$exists": false } }] };
    let update = doc! { "$set": { "deleted": true, "mutation_at": recorded_at, "expires_at": retained,
        "carrier_guess": Bson::Null,
        "distinct_numbers_seen": [], "hit_count": 0_i32, "penalty_weight": 0_i32,
        "reason": "DELETED", "first_seen_at": at, "last_seen_at": at },
    "$setOnInsert": { "ddd": prefix.chars().take(2).collect::<String>() } };
    match db
        .prefix_quarantine()
        .update_one(filter, update)
        .upsert(true)
        .await
    {
        Ok(_) => Ok(()),
        Err(error) if duplicate_key(&error) => Ok(()),
        Err(error) => Err(error),
    }
}

pub async fn delete_quarantine(
    State(state): State<AppState>,
    Path(prefix7): Path<String>,
    Query(query): Query<MutationQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    remove_quarantine(
        &state.db,
        &prefix7,
        query
            .recorded_at
            .unwrap_or_else(|| Utc::now().timestamp_millis()),
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({ "prefix_7": prefix7, "deleted": true })))
}
