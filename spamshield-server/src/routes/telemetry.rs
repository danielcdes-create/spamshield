use super::AppState;
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
use chrono::Utc;
use mongodb::Collection;
use std::collections::{HashMap, HashSet};

/// The journal filter used by persist_telemetry guarantees the append set is still valid.
fn telemetry_merge_stage(
    call: &CallTelemetryDoc,
    full: &Document,
    append: Vec<Bson>,
    now: BsonDateTime,
) -> Document {
    let feedback_at = latest_feedback_time(call);
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
        "decision_events": { "$concatArrays": [{ "$ifNull": ["$decision_events", []] }, { "$literal": append }] }
    } }
}

fn latest_feedback_time(call: &CallTelemetryDoc) -> i64 {
    call.decision_events
        .iter()
        .filter(|e| e.event_type == "USER_FEEDBACK")
        .map(|e| e.recorded_at)
        .max()
        .unwrap_or(0)
}

/// v1 clients replayed through v1.3.3 materialize exactly these additive defaults.
/// Normalize copies for comparison only: persisted evidence is never rewritten.
fn without_materialized_v1_defaults(event: &Bson) -> Bson {
    let mut event = event.clone();
    let Some(details) = event
        .as_document_mut()
        .and_then(|document| document.get_document_mut("details").ok())
    else {
        return event;
    };
    if !matches!(
        details.get("schemaVersion"),
        Some(Bson::Int32(1) | Bson::Int64(1))
    ) {
        return event;
    }
    for key in ["appVersionName", "appVersionCode"] {
        if matches!(details.get(key), Some(Bson::Null)) {
            details.remove(key);
        }
    }
    if let Ok(rules) = details.get_array_mut("evaluatedRules") {
        for rule in rules {
            if let Some(rule) = rule.as_document_mut() {
                if rule.get_bool("enabled").ok() == Some(true) {
                    rule.remove("enabled");
                }
            }
        }
    }
    event
}

fn equivalent_immutable_event(incoming: &Bson, stored: &Bson) -> bool {
    without_materialized_v1_defaults(incoming) == without_materialized_v1_defaults(stored)
}

fn index_events(events: &[Bson]) -> HashMap<&str, Vec<&Bson>> {
    let mut by_id: HashMap<&str, Vec<&Bson>> = HashMap::new();
    for event in events {
        if let Some(id) = event
            .as_document()
            .and_then(|event| event.get_str("event_id").ok())
        {
            by_id.entry(id).or_default().push(event);
        }
    }
    by_id
}

/// Validate all incoming events and return only the unseen ones in their original order.
/// Old journals may contain multiple persisted representations for the same ID.
fn appendable_events<'a>(incoming: &'a [Bson], stored: &[Bson]) -> Option<Vec<&'a Bson>> {
    let persisted = index_events(stored);
    let mut unseen: HashMap<&str, &Bson> = HashMap::new();
    let mut append = Vec::new();
    for event in incoming {
        let id = event.as_document()?.get_str("event_id").ok()?;
        if let Some(variants) = persisted.get(id) {
            if !variants
                .iter()
                .any(|variant| equivalent_immutable_event(event, variant))
            {
                return None;
            }
        } else {
            match unseen.entry(id) {
                std::collections::hash_map::Entry::Occupied(previous) => {
                    if !equivalent_immutable_event(event, previous.get()) {
                        return None;
                    }
                }
                std::collections::hash_map::Entry::Vacant(slot) => {
                    slot.insert(event);
                    append.push(event);
                }
            }
        }
    }
    Some(append)
}

#[cfg(test)]
fn events_match_persisted(call: &CallTelemetryDoc, stored: &Document) -> bool {
    let Ok(Bson::Array(incoming)) = bson::to_bson(&call.decision_events) else {
        return false;
    };
    let existing = stored
        .get_array("decision_events")
        .map(Vec::as_slice)
        .unwrap_or_default();
    appendable_events(&incoming, existing).is_some_and(|append| append.is_empty())
}

async fn persist_telemetry(
    collection: &Collection<Document>,
    call: &CallTelemetryDoc,
) -> Result<bool, mongodb::error::Error> {
    let key = doc! { "_id": &call.id };
    let now = BsonDateTime::now();
    let document = call.to_bson_document();
    let incoming = document.get_array("decision_events").unwrap();
    loop {
        let stored = collection
            .find_one(key.clone())
            .projection(doc! { "decision_events": 1 })
            .await?;
        if let Some(stored) = stored {
            let existing = stored
                .get_array("decision_events")
                .map(Vec::as_slice)
                .unwrap_or_default();
            let Some(append) = appendable_events(incoming, existing) else {
                return Ok(false);
            };
            // All journal writers only append entries. A concurrent write that can introduce
            // a new conflict also changes the array length and invalidates this snapshot.
            let mut filter = key.clone();
            match stored.get("decision_events") {
                Some(Bson::Array(events)) => {
                    filter.insert("decision_events", doc! { "$size": events.len() as i64 })
                }
                Some(original) => filter.insert(
                    "$expr",
                    doc! { "$eq": ["$decision_events", { "$literal": original }] },
                ),
                None => filter.insert("decision_events", doc! { "$exists": false }),
            };
            let stage =
                telemetry_merge_stage(call, &document, append.into_iter().cloned().collect(), now);
            if collection
                .update_one(filter, vec![stage])
                .await?
                .matched_count
                == 1
            {
                return Ok(true);
            }
            continue;
        }

        let Some(append) = appendable_events(incoming, &[]) else {
            return Ok(false);
        };
        let mut document = document.clone();
        document.insert(
            "decision_events",
            append.into_iter().cloned().collect::<Vec<_>>(),
        );
        let audit = document.get_document_mut("audit").unwrap();
        audit.insert("synced_at", now);
        audit.insert("feedback_recorded_at", latest_feedback_time(call));
        match collection.insert_one(document).await {
            Ok(_) => return Ok(true),
            Err(error) if super::rules::duplicate_key(&error) => continue,
            Err(error) => return Err(error),
        }
    }
}

/// Acknowledge only the calls and journal IDs persisted successfully. A conflicting call is
/// untouched. Other calls in the batch can succeed and be acknowledged independently.
pub async fn post_telemetry_batch(
    State(state): State<AppState>,
    Json(batch): Json<Vec<CallTelemetryDoc>>,
) -> Result<Json<BatchTelemetryResponse>, (StatusCode, String)> {
    let mut call_ids = HashSet::new();
    for call in &batch {
        if call.id.is_empty()
            || !call_ids.insert(&call.id)
            || call
                .decision_events
                .iter()
                .any(|event| event.event_id.is_empty())
            || bson::to_bson(&call.decision_events).is_err()
        {
            return Err((
                StatusCode::BAD_REQUEST,
                "Invalid or repeated call ID, invalid event ID or invalid event document".into(),
            ));
        }
    }
    let mut accepted_ids = Vec::new();
    let mut accepted_event_ids = Vec::new();
    let mut conflicting_call_ids = Vec::new();
    let collection = state.db.call_telemetry();
    for call in batch {
        if !persist_telemetry(&collection, &call)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        {
            conflicting_call_ids.push(call.id);
            continue;
        }
        accepted_ids.push(call.id);
        accepted_event_ids.extend(call.decision_events.into_iter().map(|event| event.event_id));
    }
    if accepted_ids.is_empty() && !conflicting_call_ids.is_empty() {
        return Err((
            StatusCode::CONFLICT,
            "An event ID conflicts with an immutable event; no acknowledgement".into(),
        ));
    }
    // Telemetry describes calls. Only the dedicated quarantine and feedback routes modify policy.
    Ok(Json(BatchTelemetryResponse {
        accepted_ids,
        accepted_event_ids,
        conflicting_call_ids,
        quarantine_updates: 0,
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

    fn legacy_call_with_event() -> CallTelemetryDoc {
        let mut call = call_with_event();
        call.decision_events[0].app_version = "1.3.1 (15)".into();
        call.decision_events[0].details = serde_json::json!({
            "schemaVersion": 1, "engineRevision": "decision-journal-v1", "score": 25,
            "ruleSetFingerprint": "original-configuration", "unknownLegacyFact": "preserve-me",
            "evaluatedRules": [{ "ruleId": "NOT_IN_CONTACTS", "ruleType": "HEURISTIC_WEIGHT",
                "pattern": null, "weight": 25, "priority": 12 }]
        });
        call
    }

    fn materialize_v1_3_3_defaults(call: &mut CallTelemetryDoc) {
        let details = &mut call.decision_events[0].details;
        details["appVersionName"] = serde_json::Value::Null;
        details["appVersionCode"] = serde_json::Value::Null;
        details["evaluatedRules"][0]["enabled"] = serde_json::json!(true);
    }

    #[test]
    fn legacy_replay_accepts_only_additive_v1_3_3_defaults_without_mutating_evidence() {
        for version in ["1.3.1 (15)", "1.3.2 (16)"] {
            let mut legacy = legacy_call_with_event();
            legacy.decision_events[0].app_version = version.into();
            let stored_original = legacy.to_bson_document();
            let before = stored_original.clone();
            let mut replay = legacy.clone();
            materialize_v1_3_3_defaults(&mut replay);
            assert!(events_match_persisted(&replay, &stored_original));
            assert!(events_match_persisted(&legacy, &replay.to_bson_document()));
            assert_eq!(stored_original, before);
        }
    }

    #[test]
    fn legacy_replay_still_rejects_changed_facts_types_schema_and_unknown_keys() {
        let legacy = legacy_call_with_event();
        let stored = legacy.to_bson_document();
        let mut compatible = legacy.clone();
        materialize_v1_3_3_defaults(&mut compatible);
        for (key, changed) in [
            ("score", serde_json::json!(99)),
            ("schemaVersion", serde_json::json!(2)),
            ("engineRevision", serde_json::json!("decision-journal-v2")),
            (
                "ruleSetFingerprint",
                serde_json::json!("changed-configuration"),
            ),
            ("appVersionName", serde_json::json!("1.3.3")),
            ("appVersionCode", serde_json::json!(17)),
            ("unknownLegacyFact", serde_json::json!("changed")),
            ("newUnknownFact", serde_json::Value::Null),
        ] {
            let mut changed_call = compatible.clone();
            changed_call.decision_events[0].details[key] = changed;
            assert!(
                !events_match_persisted(&changed_call, &stored),
                "must reject changed {key}"
            );
        }
        let mut disabled = compatible.clone();
        disabled.decision_events[0].details["evaluatedRules"][0]["enabled"] =
            serde_json::json!(false);
        assert!(!events_match_persisted(&disabled, &stored));
        let mut missing_unknown = compatible.clone();
        missing_unknown.decision_events[0]
            .details
            .as_object_mut()
            .unwrap()
            .remove("unknownLegacyFact");
        assert!(!events_match_persisted(&missing_unknown, &stored));
        let mut changed_envelope = compatible.clone();
        changed_envelope.decision_events[0].recorded_at += 1;
        assert!(!events_match_persisted(&changed_envelope, &stored));
        changed_envelope = compatible.clone();
        changed_envelope.decision_events[0].app_version = "1.3.3 (17)".into();
        assert!(!events_match_persisted(&changed_envelope, &stored));
        let mut changed_numeric_type = stored.clone();
        changed_numeric_type
            .get_array_mut("decision_events")
            .unwrap()[0]
            .as_document_mut()
            .unwrap()
            .get_document_mut("details")
            .unwrap()
            .insert("score", 25.0_f64);
        assert!(!events_match_persisted(&compatible, &changed_numeric_type));
    }

    #[test]
    fn v2_replay_is_strict_and_legacy_variants_remain_matchable_without_changes() {
        let modern = call_with_event();
        let mut missing_version = modern.clone();
        missing_version.decision_events[0]
            .details
            .as_object_mut()
            .unwrap()
            .remove("appVersionCode");
        assert!(!events_match_persisted(
            &missing_version,
            &modern.to_bson_document()
        ));
        let mut v2_null = modern.clone();
        v2_null.decision_events[0].details["appVersionName"] = serde_json::Value::Null;
        assert!(!events_match_persisted(
            &v2_null,
            &modern.to_bson_document()
        ));
        let legacy = legacy_call_with_event();
        let mut other_variant = legacy.clone();
        other_variant.decision_events[0].details["score"] = serde_json::json!(99);
        let mut stored = legacy.to_bson_document();
        stored
            .get_array_mut("decision_events")
            .unwrap()
            .push(bson::to_bson(&other_variant.decision_events[0]).unwrap());
        let before = stored.clone();
        assert!(events_match_persisted(&legacy, &stored));
        assert!(events_match_persisted(&other_variant, &stored));
        assert_eq!(stored, before);
    }

    #[test]
    fn bson_object_key_order_does_not_change_immutable_event_identity() {
        let call = legacy_call_with_event();
        let event = bson::to_bson(&call.decision_events[0]).unwrap();
        let original = event.as_document().unwrap();
        let reordered = original
            .iter()
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect::<Document>();
        assert!(equivalent_immutable_event(
            &event,
            &Bson::Document(reordered)
        ));
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
    fn new_journal_checks_every_record_before_selecting_unique_append_events() {
        let call = call_with_event();
        let original = bson::to_bson(&call.decision_events[0]).unwrap();
        let mut conflict = call.decision_events[0].clone();
        conflict.details["score"] = serde_json::json!(99);
        let conflict = bson::to_bson(&conflict).unwrap();
        let same_event_twice = [original.clone(), original.clone()];
        assert_eq!(
            appendable_events(&same_event_twice, &[]).unwrap(),
            vec![&original]
        );
        assert!(appendable_events(&[original.clone(), conflict.clone()], &[]).is_none());
        let mut fresh = call.decision_events[0].clone();
        fresh.event_id = "new-event".into();
        let fresh = bson::to_bson(&fresh).unwrap();
        assert!(appendable_events(&[fresh, conflict], &[original]).is_none());
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
        assert_eq!(modern.decision_vector.outcome.as_str(), "OUTGOING_CALL");
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
        use crate::models::{CallOutcome, PrefixQuarantineDoc};
        use chrono::Duration;
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
        let mut legacy = legacy_call_with_event();
        legacy.id = "legacy-variants".into();
        let mut legacy_document = legacy.to_bson_document();
        let mut variant = bson::to_bson(&legacy.decision_events[0]).unwrap();
        variant
            .as_document_mut()
            .unwrap()
            .get_document_mut("details")
            .unwrap()
            .insert("score", 99_i64);
        legacy_document
            .get_array_mut("decision_events")
            .unwrap()
            .push(variant);
        let original_legacy_events = legacy_document
            .get_array("decision_events")
            .unwrap()
            .clone();
        app_db
            .call_telemetry()
            .insert_one(legacy_document)
            .await
            .unwrap();
        materialize_v1_3_3_defaults(&mut legacy);
        assert!(
            post_telemetry_batch(State(state.clone()), Json(vec![legacy]))
                .await
                .is_ok()
        );
        let unchanged_legacy = app_db
            .call_telemetry()
            .find_one(doc! { "_id": "legacy-variants" })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            unchanged_legacy.get_array("decision_events").unwrap(),
            &original_legacy_events
        );
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
        let before_conflict = app_db
            .call_telemetry()
            .find_one(doc! { "_id": &replay.id })
            .await
            .unwrap()
            .unwrap();
        replay.decision_vector.final_score = 99;
        replay.decision_events[0].details["score"] = serde_json::json!(999);
        let mut additional_event = replay.decision_events[0].clone();
        additional_event.event_id = "fresh-but-not-accepted".into();
        replay.decision_events.push(additional_event);
        assert_eq!(
            post_telemetry_batch(State(state.clone()), Json(vec![replay.clone()]))
                .await
                .unwrap_err()
                .0,
            StatusCode::CONFLICT
        );
        let mut sibling = call_with_event();
        sibling.id = "independent-sibling".into();
        sibling.timestamp = Utc::now();
        sibling.call_details.prefix_7 = "31999998".into();
        sibling.decision_vector.outcome = CallOutcome::BLOCKED;
        let mut shadow = sibling.clone();
        shadow.id = "shadow-sibling".into();
        shadow.call_details.prefix_7 = "31999997".into();
        shadow.decision_vector.outcome = CallOutcome::SHADOW_BLOCKED;
        let partial = post_telemetry_batch(
            State(state.clone()),
            Json(vec![replay, sibling.clone(), shadow]),
        )
        .await
        .unwrap()
        .0;
        assert_eq!(
            partial.accepted_ids,
            ["independent-sibling", "shadow-sibling"]
        );
        assert_eq!(partial.conflicting_call_ids, ["call-test"]);
        assert_eq!(partial.quarantine_updates, 0);
        assert!(!partial
            .accepted_event_ids
            .iter()
            .any(|id| id == "fresh-but-not-accepted"));
        assert_eq!(
            app_db
                .call_telemetry()
                .find_one(doc! { "_id": "call-test" })
                .await
                .unwrap()
                .unwrap(),
            before_conflict
        );
        assert_eq!(
            app_db
                .prefix_quarantine()
                .count_documents(doc! {})
                .await
                .unwrap(),
            0
        );

        let mut observation = call_with_event();
        observation.id = "observed-without-request".into();
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
        let mut tied = older.clone();
        tied.event_id = "a-tied-rejected-appended-last".into();
        tied.recorded_at = blocked.recorded_at;
        observation.decision_events = vec![blocked.clone(), older, tied];
        let mut uncertain = observation.clone();
        uncertain.id = "requested-without-identified-component".into();
        blocked
            .details
            .as_object_mut()
            .unwrap()
            .remove("systemScreeningAppIsSpamShield");
        uncertain.decision_events = vec![response, blocked];
        assert!(
            post_telemetry_batch(State(state.clone()), Json(vec![observation, uncertain]))
                .await
                .is_ok()
        );
        let summary = super::super::analytics::get_analytics_summary(State(state.clone()))
            .await
            .unwrap()
            .0;
        assert_eq!(summary.total_blocked, 1);
        assert_eq!(summary.total_block_requests, 1);
        assert_eq!(summary.total_intercepted, 5);
        assert_eq!(summary.total_incoming_records, 6);

        let action_at = Utc::now().timestamp_millis();
        let mut local_feedback = sibling.decision_events[0].clone();
        local_feedback.event_id = "local-feedback".into();
        local_feedback.event_type = "USER_FEEDBACK".into();
        local_feedback.source = "USER".into();
        local_feedback.recorded_at = action_at;
        local_feedback.details = serde_json::json!({"reason": "CONFIRMED_SPAM"});
        sibling.decision_events.push(local_feedback);
        sibling.audit.user_feedback = UserFeedback::CONFIRMED_SPAM;
        let feedback_upload =
            post_telemetry_batch(State(state.clone()), Json(vec![sibling.clone()]))
                .await
                .unwrap()
                .0;
        assert_eq!(feedback_upload.accepted_ids, [sibling.id.clone()]);
        let feedback_id = "same-time-feedback";
        let feedback = FeedbackPatchRequest {
            mutation_id: Some(feedback_id.into()),
            recorded_at: Some(action_at),
            recorded_at_source: Some("USER_ACTION".into()),
            user_feedback: UserFeedback::CONFIRMED_SPAM,
        };
        for _ in 0..2 {
            let acknowledgement = patch_telemetry_feedback(
                State(state.clone()),
                Path(sibling.id.clone()),
                Json(feedback.clone()),
            )
            .await
            .unwrap()
            .0;
            assert_eq!(acknowledgement.user_feedback, UserFeedback::CONFIRMED_SPAM);
        }
        let feedback_doc = app_db
            .call_telemetry()
            .find_one(doc! { "_id": &sibling.id })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            feedback_doc
                .get_array("decision_events")
                .unwrap()
                .iter()
                .filter(|event| {
                    event
                        .as_document()
                        .and_then(|event| event.get_str("event_id").ok())
                        == Some(feedback_id)
                })
                .count(),
            1
        );
        assert_eq!(
            app_db
                .prefix_quarantine()
                .find_one(doc! { "prefix_7": &sibling.call_details.prefix_7 })
                .await
                .unwrap()
                .unwrap()
                .get_str("reason")
                .unwrap(),
            "USER_CONFIRMED_SPAM"
        );

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
        let replacement = PrefixQuarantineDoc {
            reason: "SHOULD_KEEP_EXISTING".into(),
            expires_at: q.expires_at + Duration::days(7),
            ..q.clone()
        };
        let response = super::super::rules::upsert_quarantine(
            State(state.clone()),
            axum::extract::Query(super::super::rules::MutationQuery {
                recorded_at: Some(deleted_at + 3),
                automatic: true,
            }),
            Json(replacement),
        )
        .await
        .unwrap();
        assert_eq!(response.0.quarantine.reason, "SHOULD_KEEP_EXISTING");
        assert!(!response.0.applied);
        assert_eq!(
            app_db
                .prefix_quarantine()
                .find_one(doc! { "prefix_7": &q.prefix_7 })
                .await
                .unwrap()
                .unwrap(),
            revived
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
        assert_eq!(payload.prefix_quarantine.len(), 2);

        let existing_rule = doc! { "rule_id": "BLACKLIST_MG_VOIP_PABX",
        "enabled": false, "weight": 22_i32, "updated_at": BsonDateTime::now() };
        app_db
            .active_rules()
            .insert_one(existing_rule.clone())
            .await
            .unwrap();
        let manual_cluster = PrefixQuarantineDoc {
            prefix_7: "3141011".into(),
            reason: "MANUAL".into(),
            penalty_weight: 49,
            ..q.clone()
        };
        super::super::rules::store_quarantine(&app_db, &manual_cluster, deleted_at)
            .await
            .unwrap();
        super::super::rules::remove_quarantine(&app_db, "3141013", deleted_at)
            .await
            .unwrap();
        let prefixes = doc! { "prefix_7": { "$in": ["3141011", "3141013"] } };
        let before = futures::TryStreamExt::try_collect::<Vec<_>>(
            app_db
                .prefix_quarantine()
                .find(prefixes.clone())
                .sort(doc! { "prefix_7": 1 })
                .await
                .unwrap(),
        )
        .await
        .unwrap();
        let first_sync = crate::services::anatel_sync::sync_all_providers(&app_db)
            .await
            .unwrap();
        assert_eq!(first_sync.rules_synced, 2);
        assert_eq!(first_sync.blacklist_clusters_synced, 9);
        let second_sync = crate::services::anatel_sync::sync_all_providers(&app_db)
            .await
            .unwrap();
        assert_eq!(second_sync.rules_synced, 0);
        assert_eq!(second_sync.blacklist_clusters_synced, 0);
        let after = futures::TryStreamExt::try_collect::<Vec<_>>(
            app_db
                .prefix_quarantine()
                .find(prefixes)
                .sort(doc! { "prefix_7": 1 })
                .await
                .unwrap(),
        )
        .await
        .unwrap();
        assert_eq!(before, after);
        let mut persisted_rule = app_db
            .active_rules()
            .find_one(doc! { "rule_id": "BLACKLIST_MG_VOIP_PABX" })
            .await
            .unwrap()
            .unwrap();
        persisted_rule.remove("_id");
        assert_eq!(persisted_rule, existing_rule);
        assert!(name.starts_with("spamshield_review_") && name.ends_with("_test"));
        db.drop().await.unwrap();
    }
}
