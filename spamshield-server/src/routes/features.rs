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
