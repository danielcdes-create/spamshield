pub mod analytics;
pub mod rules;
pub mod telemetry;

use crate::config::AppConfig;
use crate::db::AppDatabase;
use crate::services::anatel_sync::{self, ProviderSyncSummary};
use axum::{
    body::Body,
    extract::State,
    http::{header, Method, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, patch, post},
    Json, Router,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tower_http::{
    compression::CompressionLayer,
    cors::CorsLayer,
    trace::TraceLayer,
};

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

pub async fn apk_info_for_files(apk_path: &Path, metadata_path: &Path) -> Value {
    let apk_bytes = tokio::fs::read(apk_path).await.unwrap_or_default();
    let (version_code, version_name) = read_apk_version(metadata_path).await;
    apk_info_value(version_code, &version_name, &apk_bytes)
}

async fn read_apk_version(metadata_path: &Path) -> (i64, String) {
    let mut version_code = 1_i64;
    let mut version_name = "1.0.0".to_string();
    if let Ok(meta_str) = tokio::fs::read_to_string(metadata_path).await {
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
    (version_code, version_name)
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
            post(telemetry::post_telemetry_batch),
        )
        .route(
            "/api/v1/telemetry/:id/feedback",
            patch(telemetry::patch_telemetry_feedback),
        )
        .route("/api/v1/sync/payload", get(rules::get_sync_payload))
        .route("/api/v1/rules", post(rules::upsert_active_rule))
        .route("/api/v1/quarantine/batch", post(rules::upsert_quarantine_batch))
        .route("/api/v1/quarantine", post(rules::upsert_quarantine))
        .route(
            "/api/v1/quarantine/:prefix7",
            delete(rules::delete_quarantine),
        )
        .route("/api/v1/analytics/summary", get(analytics::get_analytics_summary))
        .layer(middleware::from_fn_with_state(state.clone(), require_bearer));

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

async fn sync_providers_handler(
    State(state): State<AppState>,
) -> Result<Json<ProviderSyncSummary>, (StatusCode, String)> {
    let summary = anatel_sync::sync_all_providers(&state.db)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(summary))
}

async fn get_apk_info(State(state): State<AppState>) -> Json<Value> {
    Json(apk_info_for_files(&state.apk_path, &state.apk_metadata_path).await)
}

async fn download_apk(State(state): State<AppState>) -> Result<Response, (StatusCode, String)> {
    let bytes = tokio::fs::read(&state.apk_path)
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
