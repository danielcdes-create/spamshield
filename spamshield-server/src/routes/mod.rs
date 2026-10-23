pub mod analytics;
pub mod rules;
pub mod telemetry;

use crate::db::AppDatabase;
use crate::services::anatel_sync::{self, ProviderSyncSummary};
use axum::{
    body::Body,
    extract::State,
    http::{header, StatusCode},
    response::Response,
    routing::{delete, get, patch, post},
    Json, Router,
};
use serde_json::{json, Value};
use tower_http::{
    compression::CompressionLayer,
    cors::{Any, CorsLayer},
    trace::TraceLayer,
};

pub fn create_router(db: AppDatabase) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .route("/api/v1/health", get(health_check))
        .route("/api/v1/apk/info", get(get_apk_info))
        .route("/api/v1/providers/sync", post(sync_providers_handler))
        .route("/apk", get(download_apk))
        .route("/spamshield.apk", get(download_apk))
        .route(
            "/api/v1/telemetry/batch",
            post(telemetry::post_telemetry_batch),
        )
        .route(
            "/api/v1/telemetry/:id/feedback",
            patch(telemetry::patch_telemetry_feedback),
        )
        .route("/api/v1/sync/payload", get(rules::get_sync_payload))
        .route("/api/v1/rules", post(rules::upsert_active_rule))
        .route("/api/v1/quarantine", post(rules::upsert_quarantine))
        .route(
            "/api/v1/quarantine/:prefix7",
            delete(rules::delete_quarantine),
        )
        .route("/api/v1/analytics/summary", get(analytics::get_analytics_summary))
        .layer(CompressionLayer::new())
        .layer(TraceLayer::new_for_http())
        .layer(cors)
        .with_state(db)
}

async fn sync_providers_handler(
    State(db): State<AppDatabase>,
) -> Result<Json<ProviderSyncSummary>, (StatusCode, String)> {
    let summary = anatel_sync::sync_all_providers(&db)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(summary))
}

async fn get_apk_info() -> Json<Value> {
    let meta_path = r"C:\Users\danie\spamshield\SpamShieldApp\app\build\outputs\apk\debug\output-metadata.json";
    let apk_path = r"C:\Users\danie\spamshield\SpamShieldApp\app\build\outputs\apk\debug\app-debug.apk";

    let mut version_code = 1_i64;
    let mut version_name = "1.0.0".to_string();

    if let Ok(meta_str) = tokio::fs::read_to_string(meta_path).await {
        if let Ok(meta_json) = serde_json::from_str::<Value>(&meta_str) {
            if let Some(first_el) = meta_json
                .get("elements")
                .and_then(|e| e.as_array())
                .and_then(|a| a.first())
            {
                if let Some(vc) = first_el.get("versionCode").and_then(|v| v.as_i64()) {
                    version_code = vc;
                }
                if let Some(vn) = first_el.get("versionName").and_then(|v| v.as_str()) {
                    version_name = vn.to_string();
                }
            }
        }
    }

    let size_bytes = tokio::fs::metadata(apk_path)
        .await
        .map(|m| m.len())
        .unwrap_or(0);

    Json(json!({
        "version_code": version_code,
        "version_name": version_name,
        "size_bytes": size_bytes,
        "download_path": "/apk"
    }))
}

async fn download_apk() -> Result<Response, (StatusCode, String)> {
    let apk_path = r"C:\Users\danie\spamshield\SpamShieldApp\app\build\outputs\apk\debug\app-debug.apk";
    let bytes = tokio::fs::read(apk_path)
        .await
        .map_err(|e| (StatusCode::NOT_FOUND, format!("APK not found: {}", e)))?;

    Response::builder()
        .status(StatusCode::OK)
        .header(
            header::CONTENT_TYPE,
            "application/vnd.android.package-archive",
        )
        .header(
            header::CONTENT_DISPOSITION,
            "attachment; filename=\"spamshield-br-debug.apk\"",
        )
        .body(Body::from(bytes))
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn health_check(State(db): State<AppDatabase>) -> Json<Value> {
    let mongo_ok = db
        .db
        .run_command(bson::doc! { "ping": 1 })
        .await
        .is_ok();
    Json(json!({
        "status": if mongo_ok { "ok" } else { "degraded" },
        "service": "spamshield-server",
        "mongodb_connected": mongo_ok,
        "tailscale_ip": "100.83.253.15"
    }))
}
