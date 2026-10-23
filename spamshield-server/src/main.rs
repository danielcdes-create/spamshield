pub mod config;
pub mod db;
pub mod models;
pub mod routes;
pub mod services;

use config::AppConfig;
use db::AppDatabase;
use std::env;
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cfg = AppConfig::from_env();

    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,spamshield_server=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    info!(
        "Starting SpamShield BR Server (DB: {}, Bind: {}, Tailscale IP: {})",
        cfg.mongodb_db_name, cfg.server_bind_addr, cfg.tailscale_ip
    );

    let app_db = AppDatabase::connect(&cfg).await?;
    app_db.init_and_seed().await?;
    if let Ok(summary) = services::anatel_sync::sync_all_providers(&app_db).await {
        info!(
            "Synced Anatel & BR Blacklist Providers: {} Anatel prefixes, {} blacklist clusters, {} rules",
            summary.anatel_prefixes_synced,
            summary.blacklist_clusters_synced,
            summary.rules_synced
        );
    }

    let args: Vec<String> = env::args().collect();
    if args.iter().any(|a| a == "--init-only") {
        info!("--init-only flag detected. Database initialization verified. Exiting.");
        return Ok(());
    }

    let app = routes::create_router(app_db);
    let listener = TcpListener::bind(&cfg.server_bind_addr).await?;
    info!(
        "SpamShield BR Server listening on http://{} (Tailscale: http://{}:8080)",
        cfg.server_bind_addr, cfg.tailscale_ip
    );

    axum::serve(listener, app).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{
        AuditInfo, BatchTelemetryResponse, CallCategory, CallDetails, CallOutcome,
        CallTelemetryDoc, DecisionVector, FeedbackPatchRequest, FeedbackPatchResponse, LineType,
        StirShakenStatus, SyncPayloadResponse, TriggeredRule, UserFeedback,
    };
    use crate::services::anatel_sync::parse_anatel_csv_parallel;
    use axum::{
        body::Body,
        http::{Request, StatusCode},
    };
    use chrono::Utc;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[test]
    fn test_anatel_csv_parallel_parser() {
        let csv_sample = "\
ddd,prefix,carrier,line_type,is_corporate_pabx
31,41011,CLARO S.A.,STFC FIXO,true
31,98621,TIM S.A.,SMP MOVEL,false
11,30031,VIVO S.A.,VOIP,true
";
        let parsed = parse_anatel_csv_parallel(csv_sample);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].prefix_7, "3141011");
        assert_eq!(parsed[0].line_type, LineType::FIXED_OR_VOIP);
        assert!(parsed[0].is_corporate_pabx_range);
        assert_eq!(parsed[1].prefix_7, "3198621");
        assert_eq!(parsed[1].line_type, LineType::MOBILE);
        assert!(!parsed[1].is_corporate_pabx_range);
    }

    #[test]
    fn test_telemetry_doc_bson_conversion_and_json_roundtrip() {
        let doc = CallTelemetryDoc {
            id: "test-uuid-1234".to_string(),
            timestamp: Utc::now(),
            device_id: "pixel-9-pro-br".to_string(),
            call_details: CallDetails {
                raw_number: "03141011230".to_string(),
                normalized_number: "+553141011230".to_string(),
                ddd: "31".to_string(),
                prefix_7: "3141011".to_string(),
                carrier_guess: Some("Claro Fixo / VoIP".to_string()),
                line_type: LineType::FIXED_OR_VOIP,
                stir_shaken_status: StirShakenStatus::FAILED,
            },
            decision_vector: DecisionVector {
                final_score: 110,
                threshold_applied: 70,
                outcome: CallOutcome::BLOCKED,
                category: CallCategory::ROBOCALL_PREFIX_CLUSTER,
                processing_latency_ms: 3.42,
                shadow_mode_active: false,
                call_duration_sec: None,
                triggered_rules: vec![
                    TriggeredRule {
                        rule_id: "NOT_IN_CONTACTS".to_string(),
                        weight: 25,
                        meta: None,
                    },
                    TriggeredRule {
                        rule_id: "STIR_SHAKEN_FAILED".to_string(),
                        weight: 85,
                        meta: Some("VERIFICATION_STATUS_FAILED".to_string()),
                    },
                ],
            },
            audit: AuditInfo {
                user_feedback: UserFeedback::UNRATED,
                synced_at: Utc::now(),
            },
        };

        let json_str = serde_json::to_string(&doc).expect("JSON serialize failed");
        let roundtrip: CallTelemetryDoc =
            serde_json::from_str(&json_str).expect("JSON deserialize failed");
        assert_eq!(roundtrip.id, "test-uuid-1234");
        assert_eq!(roundtrip.decision_vector.final_score, 110);

        let bson_doc = roundtrip.to_bson_document();
        assert_eq!(bson_doc.get_str("_id").unwrap(), "test-uuid-1234");
        assert!(bson_doc.get_datetime("timestamp").is_ok());
    }

    #[tokio::test]
    async fn test_mongodb_init_and_end_to_end_api() {
        let cfg = AppConfig::from_env();
        let app_db = AppDatabase::connect(&cfg)
            .await
            .expect("Failed to connect to MongoDB Atlas");

        app_db
            .init_and_seed()
            .await
            .expect("Failed to initialize and seed MongoDB");

        // Verify all 4 collections exist
        let collections = app_db
            .db
            .list_collection_names()
            .await
            .expect("Failed to list collections");
        assert!(collections.contains(&"call_telemetry".to_string()));
        assert!(collections.contains(&"active_rules".to_string()));
        assert!(collections.contains(&"prefix_quarantine".to_string()));
        assert!(collections.contains(&"anatel_numbering_plan".to_string()));

        let router = routes::create_router(app_db.clone());

        // 1. Test GET /api/v1/sync/payload?ddd=31&since_timestamp=0
        let req = Request::builder()
            .method("GET")
            .uri("/api/v1/sync/payload?ddd=31&since_timestamp=0")
            .body(Body::empty())
            .unwrap();
        let resp = router.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let body_bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let sync_payload: SyncPayloadResponse = serde_json::from_slice(&body_bytes).unwrap();
        assert!(sync_payload.active_rules.len() >= 10);
        assert!(sync_payload.prefix_quarantine.len() >= 4);
        assert!(sync_payload.anatel_numbering_plan.len() >= 5);

        // 2. Test POST /api/v1/telemetry/batch
        let test_id = format!("test-e2e-{}", uuid::Uuid::new_v4());
        let sample_telemetry = vec![CallTelemetryDoc {
            id: test_id.clone(),
            timestamp: Utc::now(),
            device_id: "android-harness-31".to_string(),
            call_details: CallDetails {
                raw_number: "03141011230".to_string(),
                normalized_number: "+553141011230".to_string(),
                ddd: "31".to_string(),
                prefix_7: "3141011".to_string(),
                carrier_guess: Some("Claro Fixo / VoIP".to_string()),
                line_type: LineType::FIXED_OR_VOIP,
                stir_shaken_status: StirShakenStatus::FAILED,
            },
            decision_vector: DecisionVector {
                final_score: 165,
                threshold_applied: 70,
                outcome: CallOutcome::BLOCKED,
                category: CallCategory::ROBOCALL_PREFIX_CLUSTER,
                processing_latency_ms: 2.15,
                shadow_mode_active: false,
                call_duration_sec: None,
                triggered_rules: vec![
                    TriggeredRule {
                        rule_id: "NOT_IN_CONTACTS".to_string(),
                        weight: 25,
                        meta: None,
                    },
                    TriggeredRule {
                        rule_id: "REGEX_MG_VOIP_TRUNKS".to_string(),
                        weight: 80,
                        meta: Some("314101".to_string()),
                    },
                ],
            },
            audit: AuditInfo::default(),
        }];

        let req_batch = Request::builder()
            .method("POST")
            .uri("/api/v1/telemetry/batch")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&sample_telemetry).unwrap(),
            ))
            .unwrap();
        let resp_batch = router.clone().oneshot(req_batch).await.unwrap();
        assert_eq!(resp_batch.status(), StatusCode::OK);
        let batch_bytes = resp_batch.into_body().collect().await.unwrap().to_bytes();
        let batch_res: BatchTelemetryResponse = serde_json::from_slice(&batch_bytes).unwrap();
        assert_eq!(batch_res.accepted_ids, vec![test_id.clone()]);
        assert_eq!(batch_res.quarantine_updates, 1);

        // 3. Test PATCH /api/v1/telemetry/:id/feedback
        let feedback_req = FeedbackPatchRequest {
            user_feedback: UserFeedback::CONFIRMED_SPAM,
        };
        let req_patch = Request::builder()
            .method("PATCH")
            .uri(format!("/api/v1/telemetry/{}/feedback", test_id))
            .header("content-type", "application/json")
            .body(Body::from(serde_json::to_vec(&feedback_req).unwrap()))
            .unwrap();
        let resp_patch = router.clone().oneshot(req_patch).await.unwrap();
        assert_eq!(resp_patch.status(), StatusCode::OK);
        let patch_bytes = resp_patch.into_body().collect().await.unwrap().to_bytes();
        let patch_res: FeedbackPatchResponse = serde_json::from_slice(&patch_bytes).unwrap();
        assert_eq!(patch_res.user_feedback, UserFeedback::CONFIRMED_SPAM);

        // 4. Test GET /api/v1/analytics/summary
        let req_analytics = Request::builder()
            .method("GET")
            .uri("/api/v1/analytics/summary")
            .body(Body::empty())
            .unwrap();
        let resp_analytics = router.clone().oneshot(req_analytics).await.unwrap();
        assert_eq!(resp_analytics.status(), StatusCode::OK);

        // Clean up test telemetry record
        let _ = app_db
            .call_telemetry()
            .delete_one(bson::doc! { "_id": &test_id })
            .await;
    }
}
