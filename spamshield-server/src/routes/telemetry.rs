use super::AppState;
use crate::models::{
    BatchTelemetryResponse, CallTelemetryDoc, FeedbackPatchRequest, FeedbackPatchResponse,
    PrefixQuarantineDoc, UserFeedback,
};
use crate::services::recalibrator;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::{Duration, Utc};
use mongodb::options::ReturnDocument;
use std::collections::HashMap;

/// Atomic pipeline: first event with a given ID is immutable, including concurrent retries.
pub(crate) fn telemetry_merge_stage(call: &CallTelemetryDoc, now: BsonDateTime) -> Document {
    let full = call.to_bson_document();
    let events = full
        .get_array("decision_events")
        .cloned()
        .unwrap_or_default();
    let feedback_at = call
        .decision_events
        .iter()
        .filter(|e| e.event_type == "USER_FEEDBACK")
        .map(|e| e.recorded_at)
        .max()
        .unwrap_or(0);
    let preserve_feedback =
        doc! { "$gt": [{ "$ifNull": ["$audit.feedback_recorded_at", 0_i64] }, feedback_at] };
    doc! { "$set": {
        "timestamp": { "$ifNull": ["$timestamp", { "$literal": full.get("timestamp").unwrap().clone() }] },
        "device_id": { "$ifNull": ["$device_id", { "$literal": &call.device_id }] },
        "call_details": { "$literal": full.get_document("call_details").unwrap().clone() },
        "decision_vector": { "$cond": [preserve_feedback.clone(), "$decision_vector", { "$literal": full.get_document("decision_vector").unwrap().clone() }] },
        "audit.user_feedback": { "$cond": [preserve_feedback, "$audit.user_feedback", { "$literal": call.audit.user_feedback.as_str() }] },
        "audit.feedback_recorded_at": { "$max": [{ "$ifNull": ["$audit.feedback_recorded_at", 0_i64] }, feedback_at] },
        "audit.synced_at": { "$literal": now },
        "decision_events": { "$reduce": {
            "input": { "$concatArrays": [{ "$ifNull": ["$decision_events", []] }, { "$literal": events }] },
            "initialValue": [],
            "in": { "$cond": [
                { "$in": ["$$this.event_id", { "$map": { "input": "$$value", "as": "stored", "in": "$$stored.event_id" } }] },
                "$$value", { "$concatArrays": ["$$value", ["$$this"]] }
            ] }
        } }
    } }
}

pub(crate) fn events_match_persisted(call: &CallTelemetryDoc, stored: &Document) -> bool {
    let Ok(events) = stored.get_array("decision_events") else {
        return call.decision_events.is_empty();
    };
    let by_id: HashMap<&str, &Bson> = events
        .iter()
        .filter_map(|event| {
            let id = event.as_document()?.get_str("event_id").ok()?;
            Some((id, event))
        })
        .collect();
    call.decision_events.iter().all(|event| {
        bson::to_bson(event).ok().as_ref() == by_id.get(event.event_id.as_str()).copied()
    })
}

/// All IDs are acknowledged only after their atomic upsert (including events) succeeds.
pub async fn post_telemetry_batch(
    State(state): State<AppState>,
    Json(mut batch): Json<Vec<CallTelemetryDoc>>,
) -> Result<Json<BatchTelemetryResponse>, (StatusCode, String)> {
    let mut accepted_ids = Vec::new();
    let mut accepted_event_ids = Vec::new();
    let mut quarantine_updates = 0;
    for call in &mut batch {
        if call.id.is_empty()
            || call
                .decision_events
                .iter()
                .any(|event| event.event_id.is_empty())
            || bson::to_bson(&call.decision_events).is_err()
        {
            return Err((
                StatusCode::BAD_REQUEST,
                "Invalid call/event ID or event document".into(),
            ));
        }
        call.audit.synced_at = Utc::now();
        let filter = doc! { "_id": &call.id };
        let pipeline = vec![telemetry_merge_stage(call, BsonDateTime::now())];
        let collection = state.db.call_telemetry();
        let result = collection
            .find_one_and_update(filter.clone(), pipeline.clone())
            .upsert(true)
            .return_document(ReturnDocument::After)
            .projection(doc! { "decision_events": 1 })
            .await;
        let stored = match result {
            Ok(Some(stored)) => stored,
            Ok(None) => {
                return Err((
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Telemetry write returned no document".into(),
                ))
            }
            Err(error) if super::rules::duplicate_key(&error) => collection
                .find_one_and_update(filter, pipeline)
                .return_document(ReturnDocument::After)
                .projection(doc! { "decision_events": 1 })
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
                .ok_or_else(|| {
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "Racing telemetry document disappeared".to_string(),
                    )
                })?,
            Err(error) => return Err((StatusCode::INTERNAL_SERVER_ERROR, error.to_string())),
        };
        if !events_match_persisted(call, &stored) {
            return Err((
                StatusCode::CONFLICT,
                "An event ID already belongs to a different immutable event; no acknowledgement"
                    .into(),
            ));
        }
        // Legacy compatibility: a historic retry is never a new quarantine observation.
        // The event time bounds the expiry; explicit deletion tombstones reject older evidence.
        if call.decision_vector.outcome.is_blocked_or_shadow()
            && call.audit.user_feedback != UserFeedback::FALSE_POSITIVE
            && !call.call_details.prefix_7.is_empty()
        {
            let observed = call.timestamp;
            let expiry = observed + Duration::days(7);
            if expiry > Utc::now() {
                let q = PrefixQuarantineDoc {
                    prefix_7: call.call_details.prefix_7.clone(),
                    ddd: call.call_details.ddd.clone(),
                    carrier_guess: call.call_details.carrier_guess.clone(),
                    distinct_numbers_seen: vec![call.call_details.normalized_number.clone()],
                    hit_count: 1,
                    penalty_weight: 70,
                    reason: call.decision_vector.category.as_str().to_string(),
                    first_seen_at: observed,
                    last_seen_at: observed,
                    expires_at: expiry,
                };
                super::rules::store_quarantine(&state.db, &q, observed.timestamp_millis())
                    .await
                    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
                quarantine_updates += 1;
            }
        }
        accepted_ids.push(call.id.clone());
        accepted_event_ids.extend(
            call.decision_events
                .iter()
                .map(|event| event.event_id.clone()),
        );
    }
    Ok(Json(BatchTelemetryResponse {
        accepted_ids,
        accepted_event_ids,
        quarantine_updates,
    }))
}

fn feedback_time_source(payload: &FeedbackPatchRequest) -> &str {
    if payload.recorded_at.is_none() {
        "SERVER_RECEIPT_NO_ACTION_TIME"
    } else {
        payload
            .recorded_at_source
            .as_deref()
            .unwrap_or("USER_ACTION")
    }
}

fn feedback_journal_event(
    payload: &FeedbackPatchRequest,
    operation_id: &str,
    received_at: i64,
) -> Document {
    let source = feedback_time_source(payload);
    let mut details = doc! { "reason": payload.user_feedback.as_str(), "recordedAtSource": source };
    if let Some(at) = payload.recorded_at {
        if source == "LEGACY_CALL_TIME_LOWER_BOUND" {
            details.insert("legacyCallTimeLowerBound", at);
        } else {
            details.insert("userActionAt", at);
        }
    }
    doc! { "event_id": operation_id, "recorded_at": received_at,
    "event_type": "USER_FEEDBACK", "source": "FEEDBACK_API", "app_version": "server", "details": details }
}

/// Feedback side effects are idempotent and precede the journal acknowledgement.
pub async fn patch_telemetry_feedback(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(payload): Json<FeedbackPatchRequest>,
) -> Result<Json<FeedbackPatchResponse>, (StatusCode, String)> {
    let received_at = Utc::now().timestamp_millis();
    let collection = state.db.call_telemetry();
    let existing = collection
        .find_one(doc! { "_id": &id })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .ok_or_else(|| {
            (
                StatusCode::NOT_FOUND,
                "Telemetry record not found".to_string(),
            )
        })?;
    let operation_id = payload
        .mutation_id
        .clone()
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    // A legacy lower bound orders revisions conservatively; it is not an observed action time.
    let recorded_at = payload.recorded_at.unwrap_or(received_at);
    let already_applied = existing
        .get_array("decision_events")
        .ok()
        .is_some_and(|events| {
            events.iter().any(|event| {
                event.as_document().and_then(|e| e.get_str("event_id").ok())
                    == Some(operation_id.as_str())
            })
        });
    let newer_feedback = existing
        .get_document("audit")
        .ok()
        .and_then(|audit| audit.get_i64("feedback_recorded_at").ok())
        .is_some_and(|at| at > recorded_at);
    let mut quarantine_removed_prefix = None;
    let mut recalibrated_rules = Vec::new();
    if !already_applied && !newer_feedback {
        // Replays after a network failure use the original operation time, not retry time.
        match payload.user_feedback {
            UserFeedback::FALSE_POSITIVE => {
                let (removed, rules) =
                    recalibrator::handle_false_positive(&state.db, &existing, recorded_at)
                        .await
                        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
                quarantine_removed_prefix = removed;
                recalibrated_rules = rules;
            }
            UserFeedback::CONFIRMED_SPAM => {
                recalibrator::handle_confirmed_spam(&state.db, &existing, recorded_at)
                    .await
                    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            }
            UserFeedback::UNRATED => {}
        }
        let mut vector = existing
            .get_document("decision_vector")
            .cloned()
            .unwrap_or_default();
        let mut rules = vector
            .get_array("triggered_rules")
            .cloned()
            .unwrap_or_default();
        match payload.user_feedback {
            UserFeedback::FALSE_POSITIVE => {
                rules.retain(|r| {
                    r.as_document().and_then(|d| d.get_str("rule_id").ok())
                        != Some("USER_CONFIRMED_SPAM_OVERRIDE")
                });
                if !rules.iter().any(|r| {
                    r.as_document().and_then(|d| d.get_str("rule_id").ok())
                        == Some("USER_FALSE_POSITIVE_OVERRIDE")
                }) {
                    rules.push(Bson::Document(doc! { "rule_id": "USER_FALSE_POSITIVE_OVERRIDE", "weight": -1000_i32, "meta": "Manual feedback" }));
                }
                vector.insert("outcome", "ALLOWED_CONTACT");
                vector.insert("category", "LEGITIMATE");
            }
            UserFeedback::CONFIRMED_SPAM => {
                rules.retain(|r| {
                    r.as_document().and_then(|d| d.get_str("rule_id").ok())
                        != Some("USER_FALSE_POSITIVE_OVERRIDE")
                });
                if !rules.iter().any(|r| {
                    r.as_document().and_then(|d| d.get_str("rule_id").ok())
                        == Some("USER_CONFIRMED_SPAM_OVERRIDE")
                }) {
                    rules.push(Bson::Document(doc! { "rule_id": "USER_CONFIRMED_SPAM_OVERRIDE", "weight": 100_i32, "meta": "Manual feedback" }));
                }
                if vector
                    .get_str("outcome")
                    .ok()
                    .is_some_and(|outcome| outcome.starts_with("ALLOWED"))
                {
                    vector.insert("outcome", "USER_BLOCKED_RETROACTIVE");
                }
                vector.insert("category", "USER_REPORTED_SPAM");
            }
            UserFeedback::UNRATED => {}
        }
        let score = rules
            .iter()
            .filter_map(|r| r.as_document())
            .map(|r| crate::db::doc_get_i32(r, "weight", 0))
            .sum::<i32>()
            .max(0);
        vector.insert("triggered_rules", rules);
        vector.insert("final_score", score);
        let filter = doc! { "_id": &id, "decision_events.event_id": { "$ne": &operation_id }, "$or": [
            { "audit.feedback_recorded_at": { "$lte": recorded_at } }, { "audit.feedback_recorded_at": { "$exists": false } }
        ] };
        collection.update_one(filter, doc! {
            "$set": { "audit.user_feedback": payload.user_feedback.as_str(), "audit.feedback_recorded_at": recorded_at,
                "audit.feedback_recorded_at_source": feedback_time_source(&payload),
                "audit.synced_at": BsonDateTime::now(), "decision_vector": vector },
            "$push": { "decision_events": feedback_journal_event(&payload, &operation_id, received_at) }
        }).await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(Json(FeedbackPatchResponse {
        id,
        user_feedback: payload.user_feedback,
        quarantine_removed_prefix,
        recalibrated_rules,
    }))
}

#[cfg(test)]
mod journal_regression_tests {
    use super::*;

    fn call_with_event() -> CallTelemetryDoc {
        serde_json::from_value(serde_json::json!({
            "_id": "call-test", "timestamp": 1794955837615_i64, "device_id": "test",
            "call_details": {"raw_number": "10315", "normalized_number": "10315", "ddd": "", "prefix_7": "",
                "carrier_guess": null, "line_type": "TOLL_FREE_OR_SPECIAL", "stir_shaken_status": "NOT_VERIFIED"},
            "decision_vector": {"final_score": 25, "threshold_applied": 70, "outcome": "ALLOWED_LOW_SCORE",
                "category": "UNKNOWN_UNVERIFIED", "processing_latency_ms": 1.0, "call_duration_sec": null,
                "triggered_rules": [{"rule_id": "NOT_IN_CONTACTS", "weight": 25, "meta": null}]},
            "decision_events": [{"event_id": "original-event", "recorded_at": 1794955837615_i64,
                "event_type": "SCREENING_DECISION", "source": "CALL_SCREENING_SERVICE", "app_version": "test",
                "details": {"score": 25, "schemaVersion": 2, "appVersionCode": 17}}]
        })).unwrap()
    }

    #[test]
    fn acknowledges_only_the_exact_immutable_event_returned_by_atomic_write() {
        let call = call_with_event();
        let mut persisted = call.to_bson_document();
        assert!(events_match_persisted(&call, &persisted));
        persisted.get_array_mut("decision_events").unwrap()[0]
            .as_document_mut()
            .unwrap()
            .get_document_mut("details")
            .unwrap()
            .insert("score", 99_i64);
        assert!(!events_match_persisted(&call, &persisted));
        persisted.insert("decision_events", Vec::<Bson>::new());
        assert!(!events_match_persisted(&call, &persisted));
    }

    #[test]
    fn repeated_same_id_requires_one_persisted_copy_and_preserves_new_details() {
        let mut call = call_with_event();
        let persisted = call.to_bson_document();
        call.decision_events.push(call.decision_events[0].clone());
        assert!(events_match_persisted(&call, &persisted));
        call.decision_events[1].details["appVersionCode"] = serde_json::json!(18);
        assert!(!events_match_persisted(&call, &persisted));
    }

    #[test]
    fn deletion_tombstones_are_never_parsed_as_active_quarantines() {
        let tombstone = doc! {"prefix_7": "31999999", "deleted": true,
        "expires_at": BsonDateTime::from_millis(4_102_444_800_000)};
        assert!(crate::db::parse_prefix_quarantine(&tombstone).is_none());
    }

    #[test]
    fn legacy_feedback_request_remains_accepted_without_operation_metadata() {
        let request: FeedbackPatchRequest =
            serde_json::from_value(serde_json::json!({"user_feedback": "FALSE_POSITIVE"})).unwrap();
        assert!(request.mutation_id.is_none());
        assert!(request.recorded_at.is_none());
        assert!(request.recorded_at_source.is_none());
    }

    #[test]
    fn outgoing_direction_survives_legacy_wire_and_new_enum_bson_conversion() {
        let mut wire = serde_json::to_value(call_with_event()).unwrap();
        wire["call_details"]["call_direction"] = serde_json::json!("OUTGOING");
        let legacy: CallTelemetryDoc = serde_json::from_value(wire.clone()).unwrap();
        assert_eq!(
            legacy
                .to_bson_document()
                .get_document("call_details")
                .unwrap()
                .get_str("call_direction")
                .unwrap(),
            "OUTGOING"
        );
        wire["decision_vector"]["outcome"] = serde_json::json!("OUTGOING_CALL");
        let modern: CallTelemetryDoc = serde_json::from_value(wire).unwrap();
        assert!(!modern.decision_vector.outcome.is_blocked_or_shadow());
        assert_eq!(
            modern
                .to_bson_document()
                .get_document("decision_vector")
                .unwrap()
                .get_str("outcome")
                .unwrap(),
            "OUTGOING_CALL"
        );
    }

    #[test]
    fn feedback_journal_distinguishes_received_time_observed_action_and_legacy_bound() {
        let mut request: FeedbackPatchRequest =
            serde_json::from_value(serde_json::json!({"user_feedback": "FALSE_POSITIVE"})).unwrap();
        let receipt_only = feedback_journal_event(&request, "legacy-request", 9000);
        assert_eq!(receipt_only.get_i64("recorded_at").unwrap(), 9000);
        assert!(!receipt_only
            .get_document("details")
            .unwrap()
            .contains_key("userActionAt"));
        request.recorded_at = Some(1000);
        request.recorded_at_source = Some("LEGACY_CALL_TIME_LOWER_BOUND".into());
        let estimated = feedback_journal_event(&request, "migrated-request", 9000);
        assert_eq!(estimated.get_i64("recorded_at").unwrap(), 9000);
        let details = estimated.get_document("details").unwrap();
        assert_eq!(details.get_i64("legacyCallTimeLowerBound").unwrap(), 1000);
        assert!(!details.contains_key("userActionAt"));
        request.recorded_at_source = Some("USER_FEEDBACK_EVENT".into());
        let observed = feedback_journal_event(&request, "observed-request", 9000);
        assert_eq!(observed.get_i64("recorded_at").unwrap(), 9000);
        assert_eq!(
            observed
                .get_document("details")
                .unwrap()
                .get_i64("userActionAt")
                .unwrap(),
            1000
        );
        assert!(!observed
            .get_document("details")
            .unwrap()
            .contains_key("legacyCallTimeLowerBound"));
    }

    /// Intentionally cannot read environment configuration or connect to a production database.
    #[tokio::test]
    #[ignore = "Requires isolated MongoDB at 127.0.0.1:27028; never uses .env"]
    async fn local_mongo_atomic_journal_and_quarantine_regressions() {
        use crate::db::AppDatabase;
        use mongodb::options::IndexOptions;
        use mongodb::{Client, IndexModel};
        let client = Client::with_uri_str(
            "mongodb://127.0.0.1:27028/?directConnection=true&serverSelectionTimeoutMS=3000",
        )
        .await
        .unwrap();
        let name = format!("spamshield_review_{}_test", uuid::Uuid::new_v4().simple());
        let db = client.database(&name);
        db.run_command(doc! { "ping": 1 }).await.unwrap();
        let app_db = AppDatabase {
            client,
            db: db.clone(),
        };
        app_db
            .prefix_quarantine()
            .create_index(
                IndexModel::builder()
                    .keys(doc! { "prefix_7": 1 })
                    .options(IndexOptions::builder().unique(true).build())
                    .build(),
            )
            .await
            .unwrap();
        let state = AppState {
            db: app_db.clone(),
            api_key: "local-test".into(),
            apk_path: std::path::PathBuf::new(),
            apk_metadata_path: std::path::PathBuf::new(),
            tailscale_ip: "127.0.0.1".into(),
        };
        let mut handles = Vec::new();
        for index in 0..12 {
            let mut call = call_with_event();
            call.decision_events[0].event_id = format!("racing-event-{index}");
            let state = state.clone();
            handles.push(tokio::spawn(async move {
                post_telemetry_batch(State(state), Json(vec![call])).await
            }));
        }
        for handle in handles {
            assert!(handle.await.unwrap().is_ok());
        }
        let persisted = app_db
            .call_telemetry()
            .find_one(doc! { "_id": "call-test" })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(persisted.get_array("decision_events").unwrap().len(), 12);
        let mut replay = call_with_event();
        replay.decision_events[0].event_id = "racing-event-0".into();
        assert!(
            post_telemetry_batch(State(state.clone()), Json(vec![replay.clone()]))
                .await
                .is_ok()
        );
        replay.decision_events[0].details["score"] = serde_json::json!(999);
        assert_eq!(
            post_telemetry_batch(State(state.clone()), Json(vec![replay]))
                .await
                .unwrap_err()
                .0,
            StatusCode::CONFLICT
        );

        let mut observation = call_with_event();
        let mut response = observation.decision_events[0].clone();
        response.event_id = "response".into();
        response.event_type = "SCREENING_RESPONSE_SUBMITTED".into();
        response.recorded_at = 2000;
        response.details = serde_json::json!({"disallowCall": true});
        let mut blocked = response.clone();
        blocked.event_id = "newer-blocked-observation".into();
        blocked.event_type = "CALL_LOG_OBSERVATION".into();
        blocked.source = "ANDROID_CALL_LOG".into();
        blocked.recorded_at = 4000;
        blocked.details = serde_json::json!({"systemCallType": 6, "systemBlockReason": 1, "systemScreeningAppIsSpamShield": true});
        let mut older = blocked.clone();
        older.event_id = "older-rejected-arrived-last".into();
        older.recorded_at = 3000;
        older.details = serde_json::json!({"systemCallType": 5, "systemBlockReason": 0});
        observation.decision_events = vec![response, blocked, older];
        assert!(
            post_telemetry_batch(State(state.clone()), Json(vec![observation]))
                .await
                .is_ok()
        );
        let summary = super::super::analytics::get_analytics_summary(State(state.clone()))
            .await
            .unwrap()
            .0;
        assert_eq!(summary.total_blocked, 1);

        let observed = Utc::now() - Duration::days(1);
        let mut q = PrefixQuarantineDoc {
            prefix_7: "31999999".into(),
            ddd: "31".into(),
            carrier_guess: None,
            distinct_numbers_seen: vec![],
            hit_count: 1,
            penalty_weight: 70,
            reason: "LOCAL_TEST".into(),
            first_seen_at: observed,
            last_seen_at: observed,
            expires_at: observed + Duration::days(7),
        };
        for _ in 0..2 {
            super::super::rules::store_quarantine(&app_db, &q, observed.timestamp_millis())
                .await
                .unwrap();
        }
        let stored = app_db
            .prefix_quarantine()
            .find_one(doc! { "prefix_7": &q.prefix_7 })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            stored
                .get_datetime("expires_at")
                .unwrap()
                .timestamp_millis(),
            q.expires_at.timestamp_millis()
        );
        assert_eq!(stored.get_i32("hit_count").unwrap(), 1);
        let deleted_at = Utc::now().timestamp_millis();
        super::super::rules::remove_quarantine(&app_db, &q.prefix_7, deleted_at)
            .await
            .unwrap();
        super::super::rules::store_quarantine(&app_db, &q, observed.timestamp_millis())
            .await
            .unwrap();
        let tombstone = app_db
            .prefix_quarantine()
            .find_one(doc! { "prefix_7": &q.prefix_7 })
            .await
            .unwrap()
            .unwrap();
        assert!(tombstone.get_bool("deleted").unwrap());
        q.last_seen_at = Utc::now() + Duration::milliseconds(2);
        q.expires_at = q.last_seen_at + Duration::days(7);
        super::super::rules::store_quarantine(&app_db, &q, deleted_at + 2)
            .await
            .unwrap();
        let revived = app_db
            .prefix_quarantine()
            .find_one(doc! { "prefix_7": &q.prefix_7 })
            .await
            .unwrap()
            .unwrap();
        assert!(!revived.get_bool("deleted").unwrap());
        assert_eq!(
            revived
                .get_datetime("expires_at")
                .unwrap()
                .timestamp_millis(),
            q.expires_at.timestamp_millis()
        );
        let payload = super::super::rules::get_sync_payload(
            State(state.clone()),
            axum::extract::Query(crate::models::SyncPayloadQuery {
                ddd: None,
                since_timestamp: None,
            }),
        )
        .await
        .unwrap()
        .0;
        assert!(payload.quarantine_snapshot_complete);
        assert_eq!(payload.prefix_quarantine.len(), 1);
        assert!(name.starts_with("spamshield_review_") && name.ends_with("_test"));
        db.drop().await.unwrap();
    }
}
