use super::{rules::duplicate_key, AppState};
use axum::{extract::State, http::StatusCode, Json};
use bson::{doc, Document};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::HashSet;

#[derive(Serialize, Deserialize)]
pub(super) struct FeatureEvent {
    #[serde(rename = "_id", alias = "event_id")]
    event_id: String,
    call_id: Option<String>,
    resource_id: String,
    event_type: String,
    source: String,
    recorded_at: i64,
    effective_at: i64,
    app_version: String,
    schema_version: u32,
    details: Map<String, Value>,
}

impl FeatureEvent {
    fn valid(&self) -> bool {
        fn id(value: &str) -> bool {
            value.len() == 36
                && value.bytes().enumerate().all(|(index, byte)| match index {
                    8 | 13 | 18 | 23 => byte == b'-',
                    _ => byte.is_ascii_hexdigit(),
                })
        }
        let valid_shape = match self.event_type.as_str() {
            "WAIT_SELECTOR_OPENED" | "WAIT_SELECTOR_DISMISSED" => self
                .details
                .get("selectorId")
                .and_then(Value::as_str)
                .is_some_and(id),
            "WAIT_ACTIVATED" | "WAIT_RENEWED" | "WAIT_RESTORED" | "WAIT_EXPIRED" => {
                self.details.get("window").is_some_and(Value::is_object)
            }
            "WAIT_FEEDBACK" => {
                self.call_id.is_some()
                    && self
                        .details
                        .get("decisionEventId")
                        .and_then(Value::as_str)
                        .is_some_and(id)
                    && matches!(
                        self.details.get("reason").and_then(Value::as_str),
                        Some("EXPECTED_CALL_CONFIRMED" | "SPAM_CONFIRMED")
                    )
            }
            "CAMPAIGN_DETECTED" | "CAMPAIGN_UPDATED" => {
                self.details.get("campaign").is_some_and(Value::is_object)
            }
            "CAMPAIGN_MERGED" => self
                .details
                .get("previousId")
                .and_then(Value::as_str)
                .is_some_and(id),
            "CAMPAIGN_SILENCE_ACTIVATED"
            | "CAMPAIGN_SILENCE_REMOVED"
            | "CAMPAIGN_SILENCE_EXPIRED" => {
                self.details.get("silence").is_some_and(Value::is_object)
            }
            "LAB_COMPARISON" => {
                self.call_id.is_some()
                    && self.details.get("comparison").is_some_and(Value::is_object)
            }
            "LAB_REPLAY_STARTED"
            | "LAB_SHADOW_STARTED"
            | "LAB_REPLAY_FINISHED"
            | "LAB_ENDED"
            | "LAB_DISCARDED"
            | "LAB_APPLIED" => self.details.get("experiment").is_some_and(Value::is_object),
            _ => false,
        };
        self.schema_version == 1
            && id(&self.event_id)
            && id(&self.resource_id)
            && self.call_id.as_deref().is_none_or(id)
            && self.recorded_at > 0
            && self.effective_at > 0
            && !self.app_version.is_empty()
            && matches!(
                self.source.as_str(),
                "DASHBOARD_SHIELD"
                    | "AUDIT_USER"
                    | "CAMPAIGN_RECONCILIATION"
                    | "CLOCK"
                    | "RULE_LAB"
                    | "REPLAY"
                    | "PROSPECTIVE_SHADOW"
            )
            && valid_shape
    }
}

#[derive(Default, Serialize)]
pub(super) struct FeatureBatchAck {
    accepted_event_ids: Vec<String>,
    conflicting_event_ids: Vec<String>,
}

pub(super) async fn post_feature_batch(
    State(state): State<AppState>,
    Json(batch): Json<Vec<FeatureEvent>>,
) -> Result<Json<FeatureBatchAck>, (StatusCode, &'static str)> {
    let mut ids = HashSet::new();
    let mut documents = Vec::with_capacity(batch.len());
    for event in &batch {
        if !event.valid() || !ids.insert(&event.event_id) {
            return Err((StatusCode::BAD_REQUEST, "Invalid or repeated feature event"));
        }
        documents.push(
            bson::to_document(event)
                .map_err(|_| (StatusCode::BAD_REQUEST, "Invalid feature event document"))?,
        );
    }

    let collection = state.db.db.collection::<Document>("feature_events");
    let mut ack = FeatureBatchAck::default();
    let storage_error = |_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Feature journal storage unavailable",
        )
    };
    for (event, document) in batch.into_iter().zip(documents) {
        let equivalent = match collection.insert_one(&document).await {
            Ok(_) => true,
            Err(error) if duplicate_key(&error) => {
                let stored = collection
                    .find_one(doc! { "_id": &event.event_id })
                    .await
                    .map_err(storage_error)?;
                stored.as_ref() == Some(&document)
            }
            Err(error) => return Err(storage_error(error)),
        };
        if equivalent {
            ack.accepted_event_ids.push(event.event_id);
        } else {
            ack.conflicting_event_ids.push(event.event_id);
        }
    }

    if ack.accepted_event_ids.is_empty() && !ack.conflicting_event_ids.is_empty() {
        return Err((StatusCode::CONFLICT, "Immutable feature event conflict"));
    }
    Ok(Json(ack))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn feature_envelope_round_trips_without_a_call_and_keeps_unknown_details() {
        let event: FeatureEvent = serde_json::from_value(json!({
            "event_id": "00000000-0000-4000-8000-000000000001",
            "resource_id": "00000000-0000-4000-8000-000000000002",
            "event_type": "WAIT_SELECTOR_OPENED",
            "source": "DASHBOARD_SHIELD",
            "recorded_at": 123,
            "effective_at": 122,
            "app_version": "1.3.5 (19)",
            "schema_version": 1,
            "details": {"selectorId": "00000000-0000-4000-8000-000000000002", "future": {"field": true}}
        })).unwrap();
        assert!(event.valid());
        let stored = bson::to_document(&event).unwrap();
        assert_eq!(stored.get_str("_id").unwrap(), event.event_id);
        let repeated: FeatureEvent = bson::from_document(stored.clone()).unwrap();
        assert_eq!(bson::to_document(&repeated).unwrap(), stored);
        assert!(stored
            .get_document("details")
            .unwrap()
            .contains_key("future"));

        let mut invalid = repeated;
        invalid.event_type = "LAB_COMPARISON".into();
        assert!(!invalid.valid());
    }

    #[test]
    fn feature_event_validates_all_event_families_and_rejects_malformed_envelopes() {
        let u1 = "00000000-0000-4000-8000-000000000011";
        let u2 = "00000000-0000-4000-8000-000000000012";
        let u3 = "00000000-0000-4000-8000-000000000013";
        let valid_cases = [
            json!({"event_id": u1, "resource_id": u2, "event_type": "WAIT_ACTIVATED", "source": "DASHBOARD_SHIELD", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"window": {"id": u2}}}),
            json!({"event_id": u1, "call_id": u3, "resource_id": u2, "event_type": "WAIT_FEEDBACK", "source": "AUDIT_USER", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"decisionEventId": u2, "reason": "EXPECTED_CALL_CONFIRMED"}}),
            json!({"event_id": u1, "resource_id": u2, "event_type": "CAMPAIGN_DETECTED", "source": "CAMPAIGN_RECONCILIATION", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"campaign": {"id": u2}}}),
            json!({"event_id": u1, "resource_id": u2, "event_type": "CAMPAIGN_MERGED", "source": "CAMPAIGN_RECONCILIATION", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"previousId": u3}}),
            json!({"event_id": u1, "resource_id": u2, "event_type": "CAMPAIGN_SILENCE_EXPIRED", "source": "CLOCK", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"silence": {"id": u3}}}),
            json!({"event_id": u1, "call_id": u3, "resource_id": u2, "event_type": "LAB_COMPARISON", "source": "REPLAY", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"comparison": {"direction": "UNCHANGED"}}}),
            json!({"event_id": u1, "resource_id": u2, "event_type": "LAB_SHADOW_STARTED", "source": "RULE_LAB", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"experiment": {"id": u2}}}),
        ];
        for case in valid_cases {
            let parsed: FeatureEvent = serde_json::from_value(case.clone()).unwrap();
            assert!(parsed.valid(), "expected valid case: {case}");
        }

        let invalid_cases = [
            // Invalid UUID
            json!({"event_id": "bad-uuid", "resource_id": u2, "event_type": "WAIT_ACTIVATED", "source": "DASHBOARD_SHIELD", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"window": {}}}),
            // Unsupported schema_version
            json!({"event_id": u1, "resource_id": u2, "event_type": "WAIT_ACTIVATED", "source": "DASHBOARD_SHIELD", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 2, "details": {"window": {}}}),
            // Unknown source
            json!({"event_id": u1, "resource_id": u2, "event_type": "WAIT_ACTIVATED", "source": "UNKNOWN_SOURCE", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"window": {}}}),
            // WAIT_FEEDBACK missing call_id
            json!({"event_id": u1, "resource_id": u2, "event_type": "WAIT_FEEDBACK", "source": "AUDIT_USER", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"decisionEventId": u2, "reason": "SPAM_CONFIRMED"}}),
            // CAMPAIGN_MERGED invalid previousId
            json!({"event_id": u1, "resource_id": u2, "event_type": "CAMPAIGN_MERGED", "source": "CAMPAIGN_RECONCILIATION", "recorded_at": 10, "effective_at": 10, "app_version": "1.4.1", "schema_version": 1, "details": {"previousId": "not-uuid"}}),
        ];
        for case in invalid_cases {
            let parsed: FeatureEvent = serde_json::from_value(case.clone()).unwrap();
            assert!(!parsed.valid(), "expected invalid case: {case}");
        }
    }

    #[tokio::test]
    async fn post_feature_batch_validates_auth_duplicates_and_body_limits_before_db() {
        use crate::db::AppDatabase;
        use crate::routes::TELEMETRY_BATCH_BODY_LIMIT;
        use axum::{body::Body, http::Request};
        use http_body_util::BodyExt;
        use mongodb::options::ClientOptions;
        use std::time::Duration;
        use tower::ServiceExt;

        let mut options = ClientOptions::parse("mongodb://127.0.0.1:1").await.unwrap();
        options.server_selection_timeout = Some(Duration::from_millis(150));
        options.connect_timeout = Some(Duration::from_millis(150));
        let client = mongodb::Client::with_options(options).unwrap();
        let router = super::super::create_router(AppState {
            db: AppDatabase {
                client: client.clone(),
                db: client.database("feature_validation_unit"),
            },
            api_key: "feat-unit-key".into(),
            apk_path: Default::default(),
            apk_metadata_path: Default::default(),
            tailscale_ip: "127.0.0.1".into(),
        });

        // 1. Empty batch succeeds with 200 OK without touching MongoDB
        let empty_resp = router
            .clone()
            .oneshot(
                Request::post("/api/v1/telemetry/features/batch")
                    .header("authorization", "Bearer feat-unit-key")
                    .header("content-type", "application/json")
                    .body(Body::from("[]"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(empty_resp.status(), StatusCode::OK);

        // 2. Duplicate event_id in the same batch is rejected with 400 BAD_REQUEST before DB
        let ev = json!({
            "event_id": "00000000-0000-4000-8000-000000000099",
            "resource_id": "00000000-0000-4000-8000-000000000098",
            "event_type": "WAIT_ACTIVATED",
            "source": "DASHBOARD_SHIELD",
            "recorded_at": 100,
            "effective_at": 100,
            "app_version": "1.4.1",
            "schema_version": 1,
            "details": {"window": {"id": "00000000-0000-4000-8000-000000000098"}}
        });
        let dup_resp = router
            .clone()
            .oneshot(
                Request::post("/api/v1/telemetry/features/batch")
                    .header("authorization", "Bearer feat-unit-key")
                    .header("content-type", "application/json")
                    .body(Body::from(json!([&ev, &ev]).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(dup_resp.status(), StatusCode::BAD_REQUEST);

        // 3. Missing bearer returns 401 UNAUTHORIZED
        let unauth_resp = router
            .clone()
            .oneshot(
                Request::post("/api/v1/telemetry/features/batch")
                    .header("content-type", "application/json")
                    .body(Body::from("[]"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauth_resp.status(), StatusCode::UNAUTHORIZED);

        // 4. Payload exceeding TELEMETRY_BATCH_BODY_LIMIT returns 413 with TELEMETRY_BATCH_TOO_LARGE JSON
        let mut oversized = vec![b' '; TELEMETRY_BATCH_BODY_LIMIT + 1];
        oversized[0] = b'[';
        oversized[TELEMETRY_BATCH_BODY_LIMIT] = b']';
        let too_large_resp = router
            .oneshot(
                Request::post("/api/v1/telemetry/features/batch")
                    .header("authorization", "Bearer feat-unit-key")
                    .header("content-type", "application/json")
                    .body(Body::from(oversized))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(too_large_resp.status(), StatusCode::PAYLOAD_TOO_LARGE);
        let body_bytes = too_large_resp
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes();
        let err_json: Value = serde_json::from_slice(&body_bytes).unwrap();
        assert_eq!(err_json["error"], "TELEMETRY_BATCH_TOO_LARGE");
    }

    #[tokio::test]
    #[ignore = "Requires isolated MongoDB at 127.0.0.1:27028; never uses .env"]
    async fn isolated_feature_route_preserves_original_and_acknowledges_partial_batch() {
        use crate::db::AppDatabase;
        use axum::{
            body::{to_bytes, Body},
            http::Request,
        };
        use mongodb::Client;
        use tower::ServiceExt;
        let client = Client::with_uri_str(
            "mongodb://127.0.0.1:27028/?directConnection=true&serverSelectionTimeoutMS=3000",
        )
        .await
        .unwrap();
        let db = client.database(&format!(
            "spamshield_features_{}_test",
            uuid::Uuid::new_v4().simple()
        ));
        let router = super::super::create_router(AppState {
            db: AppDatabase {
                client,
                db: db.clone(),
            },
            api_key: "feature-test".into(),
            apk_path: Default::default(),
            apk_metadata_path: Default::default(),
            tailscale_ip: "127.0.0.1".into(),
        });
        let id = "00000000-0000-4000-8000-000000000001";
        let second_id = "00000000-0000-4000-8000-000000000003";
        let event = json!({
            "event_id": id,
            "resource_id": "00000000-0000-4000-8000-000000000002",
            "event_type": "WAIT_SELECTOR_OPENED",
            "source": "DASHBOARD_SHIELD",
            "recorded_at": 123,
            "effective_at": 122,
            "app_version": "1.3.5 (19)",
            "schema_version": 1,
            "details": {"selectorId": "00000000-0000-4000-8000-000000000002"}
        });
        let post = |payload: Value| {
            router.clone().oneshot(
                Request::post("/api/v1/telemetry/features/batch")
                    .header("authorization", "Bearer feature-test")
                    .header("content-type", "application/json")
                    .body(Body::from(payload.to_string()))
                    .unwrap(),
            )
        };
        async fn read(response: axum::response::Response) -> Value {
            assert_eq!(response.status(), StatusCode::OK);
            serde_json::from_slice(&to_bytes(response.into_body(), 4096).await.unwrap()).unwrap()
        }
        assert_eq!(
            read(post(json!([&event])).await.unwrap()).await["accepted_event_ids"],
            json!([id])
        );
        assert_eq!(
            read(post(json!([&event])).await.unwrap()).await["accepted_event_ids"],
            json!([id])
        );
        let mut conflict = event.clone();
        conflict["effective_at"] = json!(121);
        let mut second = event.clone();
        second["event_id"] = json!(second_id);
        let partial = read(post(json!([&conflict, second])).await.unwrap()).await;
        assert_eq!(partial["accepted_event_ids"], json!([second_id]));
        assert_eq!(partial["conflicting_event_ids"], json!([id]));
        assert_eq!(
            post(json!([conflict])).await.unwrap().status(),
            StatusCode::CONFLICT
        );
        let stored = db
            .collection::<Document>("feature_events")
            .find_one(doc! { "_id": id })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(stored.get_i64("effective_at").unwrap(), 122);
        db.drop().await.unwrap();
    }
}
