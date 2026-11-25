pub mod analytics;
mod features;
pub mod rules;
pub mod telemetry;

use crate::config::AppConfig;
use crate::db::AppDatabase;
use crate::services::anatel_sync::{self, ProviderSyncSummary};
use axum::{
    body::Body,
    extract::{DefaultBodyLimit, State},
    http::{header, Method, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, patch, post},
    Json, Router,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tower_http::{compression::CompressionLayer, cors::CorsLayer, trace::TraceLayer};

/// Batches include the immutable journal, not just the latest row. Keep this route bounded
/// without applying Axum's 2 MiB default to the app's existing 100-call batches.
pub const TELEMETRY_BATCH_BODY_LIMIT: usize = 16 * 1024 * 1024;

#[derive(Clone)]
pub struct AppState {
    pub db: AppDatabase,
    pub api_key: String,
    pub apk_path: PathBuf,
    pub apk_metadata_path: PathBuf,
    pub tailscale_ip: String,
}

impl AppState {
    pub fn new(cfg: &AppConfig, db: AppDatabase) -> Self {
        Self {
            db,
            api_key: cfg.api_key.clone(),
            apk_path: cfg.apk_storage_path.clone(),
            apk_metadata_path: cfg.apk_metadata_path.clone(),
            tailscale_ip: cfg.tailscale_ip.clone(),
        }
    }
}

/// Accepts only `Authorization: Bearer <key>` when the key matches configuration.
/// An empty configured key rejects every caller, including a blank bearer.
pub fn bearer_is_accepted(authorization: Option<&str>, configured_key: &str) -> bool {
    if configured_key.is_empty() {
        return false;
    }
    let Some(header) = authorization else {
        return false;
    };
    let Some(presented) = header.strip_prefix("Bearer ") else {
        return false;
    };
    presented == configured_key
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn apk_info_value(version_code: i64, version_name: &str, apk_bytes: &[u8]) -> Value {
    json!({
        "version_code": version_code,
        "version_name": version_name,
        "size_bytes": apk_bytes.len() as u64,
        "sha256_checksum": sha256_hex(apk_bytes),
        "download_path": "/apk"
    })
}

async fn read_apk(apk_path: &Path) -> Result<Vec<u8>, (StatusCode, &'static str)> {
    match tokio::fs::read(apk_path).await {
        Ok(bytes) if !bytes.is_empty() => Ok(bytes),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            Err((StatusCode::NOT_FOUND, "APK not found"))
        }
        _ => Err((StatusCode::SERVICE_UNAVAILABLE, "APK unavailable")),
    }
}

pub async fn apk_info_for_files(
    apk_path: &Path,
    metadata_path: &Path,
) -> Result<Value, (StatusCode, &'static str)> {
    let unavailable = (
        StatusCode::SERVICE_UNAVAILABLE,
        "APK metadata unavailable or invalid",
    );
    let apk_bytes = read_apk(apk_path).await?;
    let metadata = tokio::fs::read_to_string(metadata_path)
        .await
        .map_err(|_| unavailable)?;
    let metadata: Value =
        serde_json::from_str(metadata.trim_start_matches('\u{feff}')).map_err(|_| unavailable)?;
    let manifest =
        metadata.get("sha256_checksum").is_some() || metadata.get("version_code").is_some();
    let (code, name) = if manifest {
        (
            metadata["version_code"].as_i64(),
            metadata["version_name"].as_str(),
        )
    } else {
        // Continue serving deployments configured with Gradle's output-metadata.json.
        match metadata
            .get("elements")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
        {
            Some([element]) => (
                element["versionCode"].as_i64(),
                element["versionName"].as_str(),
            ),
            _ => return Err(unavailable),
        }
    };
    let code = code.filter(|code| *code > 0).ok_or(unavailable)?;
    let name = name
        .filter(|name| !name.trim().is_empty())
        .ok_or(unavailable)?;
    let info = apk_info_value(code, name, &apk_bytes);
    if manifest
        && (metadata.get("size_bytes") != info.get("size_bytes")
            || metadata.get("sha256_checksum") != info.get("sha256_checksum"))
    {
        return Err((
            StatusCode::SERVICE_UNAVAILABLE,
            "APK does not match its release manifest",
        ));
    }
    Ok(info)
}

async fn require_bearer(
    State(state): State<AppState>,
    req: Request<Body>,
    next: Next,
) -> Result<Response, StatusCode> {
    let presented = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok());
    if !bearer_is_accepted(presented, &state.api_key) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(next.run(req).await)
}

pub fn create_router(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_methods([Method::GET, Method::POST, Method::PATCH, Method::DELETE])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE, header::ACCEPT]);

    let protected = Router::new()
        .route("/api/v1/providers/sync", post(sync_providers_handler))
        .route(
            "/api/v1/telemetry/batch",
            post(telemetry::post_telemetry_batch)
                .route_layer(middleware::from_fn(telemetry_request_result))
                .layer(DefaultBodyLimit::max(TELEMETRY_BATCH_BODY_LIMIT)),
        )
        .route(
            "/api/v1/telemetry/features/batch",
            post(features::post_feature_batch)
                .route_layer(middleware::from_fn(telemetry_request_result))
                .layer(DefaultBodyLimit::max(TELEMETRY_BATCH_BODY_LIMIT)),
        )
        .route(
            "/api/v1/telemetry/:id/feedback",
            patch(telemetry::patch_telemetry_feedback),
        )
        .route("/api/v1/sync/payload", get(rules::get_sync_payload))
        .route("/api/v1/rules", post(rules::upsert_active_rule))
        .route(
            "/api/v1/quarantine/batch",
            post(rules::upsert_quarantine_batch),
        )
        .route("/api/v1/quarantine", post(rules::upsert_quarantine))
        .route(
            "/api/v1/quarantine/:prefix7",
            delete(rules::delete_quarantine),
        )
        .route(
            "/api/v1/analytics/summary",
            get(analytics::get_analytics_summary),
        )
        .layer(middleware::from_fn_with_state(
            state.clone(),
            require_bearer,
        ));

    let public = Router::new()
        .route("/api/v1/health", get(health_check))
        .route("/api/v1/apk/info", get(get_apk_info))
        .route("/apk", get(download_apk))
        .route("/spamshield.apk", get(download_apk));

    Router::new()
        .merge(public)
        .merge(protected)
        .layer(CompressionLayer::new())
        .layer(TraceLayer::new_for_http())
        .layer(cors)
        .with_state(state)
}

async fn telemetry_request_result(req: Request<Body>, next: Next) -> Response {
    let declared_bytes = req
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok());
    let response = next.run(req).await;
    let status = response.status();
    if status == StatusCode::PAYLOAD_TOO_LARGE {
        tracing::warn!(declared_request_bytes = ?declared_bytes, http_status = status.as_u16(), max_bytes = TELEMETRY_BATCH_BODY_LIMIT,
            "Telemetry batch rejected by request size limit");
        return (
            status,
            Json(json!({
                "error": "TELEMETRY_BATCH_TOO_LARGE",
                "max_request_bytes": TELEMETRY_BATCH_BODY_LIMIT
            })),
        )
            .into_response();
    }
    // Sizes and status only: never log phone numbers, journal payloads or bearer credentials.
    tracing::info!(declared_request_bytes = ?declared_bytes, http_status = status.as_u16(),
        "Telemetry batch request completed");
    response
}

async fn sync_providers_handler(
    State(state): State<AppState>,
) -> Result<Json<ProviderSyncSummary>, (StatusCode, String)> {
    let summary = anatel_sync::sync_all_providers(&state.db)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(summary))
}

async fn get_apk_info(
    State(state): State<AppState>,
) -> Result<Json<Value>, (StatusCode, &'static str)> {
    apk_info_for_files(&state.apk_path, &state.apk_metadata_path)
        .await
        .map(Json)
}

async fn download_apk(State(state): State<AppState>) -> Result<Response, (StatusCode, String)> {
    let bytes = read_apk(&state.apk_path)
        .await
        .map_err(|(status, message)| (status, message.to_string()))?;

    Response::builder()
        .status(StatusCode::OK)
        .header(
            header::CONTENT_TYPE,
            "application/vnd.android.package-archive",
        )
        .header(
            header::CONTENT_DISPOSITION,
            "attachment; filename=\"spamshield-br.apk\"",
        )
        .body(Body::from(bytes))
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn health_check(State(state): State<AppState>) -> impl IntoResponse {
    let mongo_ok = state
        .db
        .db
        .run_command(bson::doc! { "ping": 1 })
        .await
        .is_ok();
    Json(json!({
        "status": if mongo_ok { "ok" } else { "degraded" },
        "service": "spamshield-server",
        "mongodb_connected": mongo_ok,
        "tailscale_ip": state.tailscale_ip
    }))
}

#[cfg(test)]
mod telemetry_body_limit_tests {
    use super::*;
    use http_body_util::BodyExt;
    use mongodb::options::ClientOptions;
    use std::time::Duration;
    use tower::ServiceExt;

    async fn router() -> Router {
        // Explicit local unreachable DB; an empty telemetry batch never accesses it.
        // No .env, initialization, seed or production configuration is loaded.
        let mut options = ClientOptions::parse("mongodb://127.0.0.1:1").await.unwrap();
        options.server_selection_timeout = Some(Duration::from_millis(200));
        options.connect_timeout = Some(Duration::from_millis(200));
        let client = mongodb::Client::with_options(options).unwrap();
        create_router(AppState {
            db: AppDatabase {
                client: client.clone(),
                db: client.database("telemetry_body_limit_test"),
            },
            api_key: "body-limit-unit-key".into(),
            apk_path: PathBuf::from("missing-unit.apk"),
            apk_metadata_path: PathBuf::from("missing-unit.json"),
            tailscale_ip: "127.0.0.1".into(),
        })
    }

    fn request(path: &str, size: usize, authorized: bool) -> Request<Body> {
        let mut body = vec![b' '; size];
        body[0] = b'[';
        body[size - 1] = b']';
        let mut request = Request::builder()
            .method("POST")
            .uri(path)
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::CONTENT_LENGTH, size.to_string());
        if authorized {
            request = request.header(header::AUTHORIZATION, "Bearer body-limit-unit-key");
        }
        request.body(Body::from(body)).unwrap()
    }

    async fn assert_empty_batch_accepted(size: usize) {
        let response = router()
            .await
            .oneshot(request("/api/v1/telemetry/batch", size, true))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let data = response.into_body().collect().await.unwrap().to_bytes();
        let ack: Value = serde_json::from_slice(&data).unwrap();
        assert_eq!(ack["accepted_ids"], json!([]));
        assert_eq!(ack["accepted_event_ids"], json!([]));
        assert_eq!(ack["quarantine_updates"], 0);
    }

    #[tokio::test]
    async fn journal_batch_above_previous_two_mib_limit_is_accepted() {
        assert_empty_batch_accepted(3 * 1024 * 1024).await;
    }

    #[tokio::test]
    async fn journal_batch_at_configured_limit_is_accepted() {
        assert_empty_batch_accepted(TELEMETRY_BATCH_BODY_LIMIT).await;
    }

    #[tokio::test]
    async fn journal_batch_above_configured_limit_is_rejected_with_explicit_bound() {
        let response = router()
            .await
            .oneshot(request(
                "/api/v1/telemetry/batch",
                TELEMETRY_BATCH_BODY_LIMIT + 1,
                true,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let rejection: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(rejection["error"], "TELEMETRY_BATCH_TOO_LARGE");
        assert_eq!(rejection["max_request_bytes"], TELEMETRY_BATCH_BODY_LIMIT);
    }

    #[tokio::test]
    async fn oversized_journal_still_requires_bearer_before_body_extraction() {
        let response = router()
            .await
            .oneshot(request(
                "/api/v1/telemetry/batch",
                TELEMETRY_BATCH_BODY_LIMIT + 1,
                false,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn unrelated_write_routes_keep_their_default_body_limit() {
        let response = router()
            .await
            .oneshot(request("/api/v1/rules", 3 * 1024 * 1024, true))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn apk_download_and_bom_metadata_and_pre_db_validation_guards_work() {
        let dir = std::env::temp_dir().join(format!("spamshield-route-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let apk_file = dir.join("release.apk");
        let meta_file = dir.join("output-metadata.json");

        let mut options = ClientOptions::parse("mongodb://127.0.0.1:1").await.unwrap();
        options.server_selection_timeout = Some(Duration::from_millis(150));
        options.connect_timeout = Some(Duration::from_millis(150));
        let client = mongodb::Client::with_options(options).unwrap();
        let app = create_router(AppState {
            db: AppDatabase {
                client: client.clone(),
                db: client.database("route_validation_test"),
            },
            api_key: "route-unit-key".into(),
            apk_path: apk_file.clone(),
            apk_metadata_path: meta_file.clone(),
            tailscale_ip: "100.101.23.111".into(),
        });

        // 1. Missing APK -> 404 NOT_FOUND on /apk
        let missing_dl = app
            .clone()
            .oneshot(Request::builder().uri("/apk").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(missing_dl.status(), StatusCode::NOT_FOUND);

        // 2. Empty 0-byte APK -> 503 SERVICE_UNAVAILABLE
        std::fs::write(&apk_file, b"").unwrap();
        let empty_dl = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/spamshield.apk")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(empty_dl.status(), StatusCode::SERVICE_UNAVAILABLE);

        // 3. Valid APK + UTF-8 BOM metadata -> 200 OK on /apk and /api/v1/apk/info
        let apk_bytes = b"PK\x03\x04spamshield-apk-payload";
        std::fs::write(&apk_file, apk_bytes).unwrap();
        let bom_meta = "\u{feff}{\"elements\":[{\"versionCode\":21,\"versionName\":\"1.4.1\"}]}";
        std::fs::write(&meta_file, bom_meta.as_bytes()).unwrap();

        let ok_dl = app
            .clone()
            .oneshot(Request::builder().uri("/apk").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(ok_dl.status(), StatusCode::OK);
        assert_eq!(
            ok_dl.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/vnd.android.package-archive"
        );
        assert!(ok_dl
            .headers()
            .get(header::CONTENT_DISPOSITION)
            .unwrap()
            .to_str()
            .unwrap()
            .contains("spamshield-br.apk"));
        let downloaded = ok_dl.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(downloaded.as_ref(), apk_bytes);

        let ok_info = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/apk/info")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(ok_info.status(), StatusCode::OK);

        // 4. POST /api/v1/rules rejects blank rule_id and REGEX_MATCH without pattern (BUG-07)
        for bad_rule in [
            json!({"rule_id": "   ", "name": "Bad", "rule_type": "HEURISTIC_WEIGHT", "pattern": null, "weight": 20, "priority": 10, "enabled": true, "updated_at": 1700000000000_i64}),
            json!({"rule_id": "CUSTOM_EMPTY_REGEX", "name": "Bad Regex", "rule_type": "REGEX_MATCH", "pattern": "   ", "weight": 50, "priority": 25, "enabled": true, "updated_at": 1700000000000_i64}),
        ] {
            let bad_resp = app
                .clone()
                .oneshot(
                    Request::builder()
                        .method("POST")
                        .uri("/api/v1/rules")
                        .header(header::AUTHORIZATION, "Bearer route-unit-key")
                        .header(header::CONTENT_TYPE, "application/json")
                        .body(Body::from(bad_rule.to_string()))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(bad_resp.status(), StatusCode::BAD_REQUEST);
        }

        // 5. POST /api/v1/telemetry/batch rejects duplicate call IDs or empty event_id with 400 BAD_REQUEST
        let sample_call = json!({
            "_id": "dup-call-1",
            "timestamp": 1700000000000_i64,
            "device_id": "dev-1",
            "call_details": {
                "raw_number": "03132221111",
                "normalized_number": "+553132221111",
                "ddd": "31",
                "prefix_7": "3132221",
                "carrier_guess": "Fixo",
                "line_type": "FIXED_OR_VOIP",
                "stir_shaken_status": "NOT_VERIFIED"
            },
            "decision_vector": {
                "final_score": 25,
                "threshold_applied": 70,
                "outcome": "ALLOWED_LOW_SCORE",
                "category": "UNKNOWN_UNVERIFIED",
                "processing_latency_ms": 1.0,
                "triggered_rules": []
            },
            "decision_events": []
        });
        let dup_batch_resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/telemetry/batch")
                    .header(header::AUTHORIZATION, "Bearer route-unit-key")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(json!([&sample_call, &sample_call]).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(dup_batch_resp.status(), StatusCode::BAD_REQUEST);

        let _ = std::fs::remove_dir_all(&dir);
    }
}
