use std::env;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub mongodb_uri: String,
    pub mongodb_direct_uri: Option<String>,
    pub mongodb_db_name: String,
    pub server_bind_addr: String,
    pub tailscale_ip: String,
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

        Self {
            mongodb_uri,
            mongodb_direct_uri,
            mongodb_db_name,
            server_bind_addr,
            tailscale_ip,
        }
    }
}
