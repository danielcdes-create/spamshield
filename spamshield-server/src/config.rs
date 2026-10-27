use std::env;
use std::path::PathBuf;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub mongodb_uri: String,
    pub mongodb_direct_uri: Option<String>,
    pub mongodb_db_name: String,
    pub server_bind_addr: String,
    pub tailscale_ip: String,
    /// Shared bearer loaded from `SPAMSHIELD_API_KEY`. Empty rejects every request.
    pub api_key: String,
    pub apk_storage_path: PathBuf,
    pub apk_metadata_path: PathBuf,
}

/// Relative fallback. Never embeds a machine-specific absolute path.
pub fn resolve_apk_storage_path(configured: Option<&str>) -> PathBuf {
    match configured.map(str::trim).filter(|s| !s.is_empty()) {
        Some(path) => PathBuf::from(path),
        None => PathBuf::from("artifacts").join("spamshield.apk"),
    }
}

pub fn resolve_apk_metadata_path(apk_path: &std::path::Path, configured: Option<&str>) -> PathBuf {
    match configured.map(str::trim).filter(|s| !s.is_empty()) {
        Some(path) => PathBuf::from(path),
        None => apk_path.with_file_name("output-metadata.json"),
    }
}

impl AppConfig {
    pub fn from_env() -> Self {
        let _ = dotenvy::dotenv();

        let mongodb_uri = env::var("MONGODB_URI").unwrap_or_else(|_| {
            "mongodb://localhost:27017/spam_shield_db".to_string()
        });

        let mongodb_direct_uri = env::var("MONGODB_DIRECT_URI").ok();

        let mongodb_db_name =
            env::var("MONGODB_DB_NAME").unwrap_or_else(|_| "spam_shield_db".to_string());

        let server_bind_addr =
            env::var("SERVER_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8080".to_string());

        let tailscale_ip =
            env::var("TAILSCALE_IP").unwrap_or_else(|_| "100.83.253.15".to_string());

        let api_key = env::var("SPAMSHIELD_API_KEY").unwrap_or_default();
        let apk_storage_path = resolve_apk_storage_path(env::var("APK_STORAGE_PATH").ok().as_deref());
        let apk_metadata_path = resolve_apk_metadata_path(
            &apk_storage_path,
            env::var("APK_METADATA_PATH").ok().as_deref(),
        );

        Self {
            mongodb_uri,
            mongodb_direct_uri,
            mongodb_db_name,
            server_bind_addr,
            tailscale_ip,
            api_key,
            apk_storage_path,
            apk_metadata_path,
        }
    }
}
