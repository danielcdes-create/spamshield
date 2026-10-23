use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::{DateTime, TimeZone, Utc};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

pub mod flexible_datetime {
    use super::*;
    use serde::de::{Error, MapAccess, Visitor};
    use std::fmt;

    pub fn serialize<S>(dt: &DateTime<Utc>, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&dt.to_rfc3339())
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<DateTime<Utc>, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct DateTimeVisitor;

        impl<'de> Visitor<'de> for DateTimeVisitor {
            type Value = DateTime<Utc>;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("RFC3339 string, epoch millis integer, or BSON DateTime")
            }

            fn visit_str<E>(self, v: &str) -> Result<Self::Value, E>
            where
                E: Error,
            {
                if let Ok(dt) = DateTime::parse_from_rfc3339(v) {
                    return Ok(dt.with_timezone(&Utc));
                }
                if let Ok(ms) = v.parse::<i64>() {
                    if let Some(dt) = Utc.timestamp_millis_opt(ms).single() {
                        return Ok(dt);
                    }
                }
                Err(E::custom(format!("Invalid datetime string: {}", v)))
            }

            fn visit_i64<E>(self, v: i64) -> Result<Self::Value, E>
            where
                E: Error,
            {
                // Detect seconds vs milliseconds
                let millis = if v < 100_000_000_000 { v * 1000 } else { v };
                Utc.timestamp_millis_opt(millis)
                    .single()
                    .ok_or_else(|| E::custom(format!("Invalid epoch timestamp: {}", v)))
            }

            fn visit_u64<E>(self, v: u64) -> Result<Self::Value, E>
            where
                E: Error,
            {
                self.visit_i64(v as i64)
            }

            fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
            where
                A: MapAccess<'de>,
            {
                // Handle BSON DateTime {"$date": ...}
                while let Some(key) = map.next_key::<String>()? {
                    if key == "$date" {
                        let val: serde_json::Value = map.next_value()?;
                        if let Some(s) = val.as_str() {
                            return self.visit_str(s);
                        }
                        if let Some(n) = val.as_i64() {
                            return self.visit_i64(n);
                        }
                        if let Some(obj) = val.as_object() {
                            if let Some(num_long) = obj.get("$numberLong").and_then(|v| v.as_str())
                            {
                                if let Ok(ms) = num_long.parse::<i64>() {
                                    return self.visit_i64(ms);
                                }
                            }
                        }
                    } else {
                        let _: serde::de::IgnoredAny = map.next_value()?;
                    }
                }
                Err(A::Error::custom("Expected $date in BSON DateTime map"))
            }
        }

        deserializer.deserialize_any(DateTimeVisitor)
    }
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum LineType {
    MOBILE,
    FIXED_OR_VOIP,
    TOLL_FREE_OR_SPECIAL,
    UNKNOWN,
}

impl LineType {
    pub fn as_str(&self) -> &'static str {
        match self {
            LineType::MOBILE => "MOBILE",
            LineType::FIXED_OR_VOIP => "FIXED_OR_VOIP",
            LineType::TOLL_FREE_OR_SPECIAL => "TOLL_FREE_OR_SPECIAL",
            LineType::UNKNOWN => "UNKNOWN",
        }
    }
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum StirShakenStatus {
    PASSED,
    FAILED,
    NOT_VERIFIED,
}

impl StirShakenStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            StirShakenStatus::PASSED => "PASSED",
            StirShakenStatus::FAILED => "FAILED",
            StirShakenStatus::NOT_VERIFIED => "NOT_VERIFIED",
        }
    }
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CallOutcome {
    BLOCKED,
    SHADOW_BLOCKED,
    ALLOWED_CONTACT,
    ALLOWED_EMERGENCY_REPEAT,
    ALLOWED_OUTBOUND_GRACE,
    ALLOWED_LOW_SCORE,
}

impl CallOutcome {
    pub fn as_str(&self) -> &'static str {
        match self {
            CallOutcome::BLOCKED => "BLOCKED",
            CallOutcome::SHADOW_BLOCKED => "SHADOW_BLOCKED",
            CallOutcome::ALLOWED_CONTACT => "ALLOWED_CONTACT",
            CallOutcome::ALLOWED_EMERGENCY_REPEAT => "ALLOWED_EMERGENCY_REPEAT",
            CallOutcome::ALLOWED_OUTBOUND_GRACE => "ALLOWED_OUTBOUND_GRACE",
            CallOutcome::ALLOWED_LOW_SCORE => "ALLOWED_LOW_SCORE",
        }
    }

    pub fn is_blocked_or_shadow(&self) -> bool {
        matches!(self, CallOutcome::BLOCKED | CallOutcome::SHADOW_BLOCKED)
    }
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CallCategory {
    ROBOCALL_PREFIX_CLUSTER,
    SPOOFING_STIR_FAILED,
    KNOWN_BLACKLIST,
    PING_CALL_ROBOCALL,
    LEGITIMATE,
    UNKNOWN_UNVERIFIED,
}

impl CallCategory {
    pub fn as_str(&self) -> &'static str {
        match self {
            CallCategory::ROBOCALL_PREFIX_CLUSTER => "ROBOCALL_PREFIX_CLUSTER",
            CallCategory::SPOOFING_STIR_FAILED => "SPOOFING_STIR_FAILED",
            CallCategory::KNOWN_BLACKLIST => "KNOWN_BLACKLIST",
            CallCategory::PING_CALL_ROBOCALL => "PING_CALL_ROBOCALL",
            CallCategory::LEGITIMATE => "LEGITIMATE",
            CallCategory::UNKNOWN_UNVERIFIED => "UNKNOWN_UNVERIFIED",
        }
    }
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum UserFeedback {
    UNRATED,
    CONFIRMED_SPAM,
    FALSE_POSITIVE,
}

impl UserFeedback {
    pub fn as_str(&self) -> &'static str {
        match self {
            UserFeedback::UNRATED => "UNRATED",
            UserFeedback::CONFIRMED_SPAM => "CONFIRMED_SPAM",
            UserFeedback::FALSE_POSITIVE => "FALSE_POSITIVE",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallDetails {
    pub raw_number: String,
    pub normalized_number: String,
    pub ddd: String,
    pub prefix_7: String,
    pub carrier_guess: Option<String>,
    pub line_type: LineType,
    pub stir_shaken_status: StirShakenStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TriggeredRule {
    pub rule_id: String,
    pub weight: i32,
    pub meta: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DecisionVector {
    pub final_score: i32,
    pub threshold_applied: i32,
    pub outcome: CallOutcome,
    pub category: CallCategory,
    pub processing_latency_ms: f64,
    #[serde(default)]
    pub shadow_mode_active: bool,
    pub call_duration_sec: Option<i32>,
    pub triggered_rules: Vec<TriggeredRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditInfo {
    pub user_feedback: UserFeedback,
    #[serde(with = "flexible_datetime", default = "Utc::now")]
    pub synced_at: DateTime<Utc>,
}

impl Default for AuditInfo {
    fn default() -> Self {
        Self {
            user_feedback: UserFeedback::UNRATED,
            synced_at: Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallTelemetryDoc {
    #[serde(rename = "_id")]
    pub id: String,
    #[serde(with = "flexible_datetime")]
    pub timestamp: DateTime<Utc>,
    pub device_id: String,
    pub call_details: CallDetails,
    pub decision_vector: DecisionVector,
    #[serde(default)]
    pub audit: AuditInfo,
}

impl CallTelemetryDoc {
    /// Converts into a strict BSON Document conforming 100% to the MongoDB `$jsonSchema` validator
    pub fn to_bson_document(&self) -> Document {
        let triggered_rules_bson: Vec<Bson> = self
            .decision_vector
            .triggered_rules
            .iter()
            .map(|r| {
                let meta_bson = match &r.meta {
                    Some(m) => Bson::String(m.clone()),
                    None => Bson::Null,
                };
                Bson::Document(doc! {
                    "rule_id": &r.rule_id,
                    "weight": Bson::Int32(r.weight),
                    "meta": meta_bson,
                })
            })
            .collect();

        let carrier_bson = match &self.call_details.carrier_guess {
            Some(c) => Bson::String(c.clone()),
            None => Bson::Null,
        };

        let duration_bson = match self.decision_vector.call_duration_sec {
            Some(d) => Bson::Int32(d),
            None => Bson::Null,
        };

        doc! {
            "_id": &self.id,
            "timestamp": BsonDateTime::from_chrono(self.timestamp),
            "device_id": &self.device_id,
            "call_details": {
                "raw_number": &self.call_details.raw_number,
                "normalized_number": &self.call_details.normalized_number,
                "ddd": &self.call_details.ddd,
                "prefix_7": &self.call_details.prefix_7,
                "carrier_guess": carrier_bson,
                "line_type": self.call_details.line_type.as_str(),
                "stir_shaken_status": self.call_details.stir_shaken_status.as_str(),
            },
            "decision_vector": {
                "final_score": Bson::Int32(self.decision_vector.final_score),
                "threshold_applied": Bson::Int32(self.decision_vector.threshold_applied),
                "outcome": self.decision_vector.outcome.as_str(),
                "category": self.decision_vector.category.as_str(),
                "processing_latency_ms": Bson::Double(self.decision_vector.processing_latency_ms),
                "shadow_mode_active": Bson::Boolean(self.decision_vector.shadow_mode_active),
                "call_duration_sec": duration_bson,
                "triggered_rules": triggered_rules_bson,
            },
            "audit": {
                "user_feedback": self.audit.user_feedback.as_str(),
                "synced_at": BsonDateTime::from_chrono(self.audit.synced_at),
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveRuleDoc {
    pub rule_id: String,
    pub name: String,
    pub rule_type: String,
    pub pattern: Option<String>,
    pub weight: i32,
    pub priority: i32,
    pub enabled: bool,
    #[serde(with = "flexible_datetime")]
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrefixQuarantineDoc {
    pub prefix_7: String,
    pub ddd: String,
    pub carrier_guess: Option<String>,
    pub distinct_numbers_seen: Vec<String>,
    pub hit_count: i32,
    pub penalty_weight: i32,
    pub reason: String,
    #[serde(with = "flexible_datetime")]
    pub first_seen_at: DateTime<Utc>,
    #[serde(with = "flexible_datetime")]
    pub last_seen_at: DateTime<Utc>,
    #[serde(with = "flexible_datetime")]
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnatelNumberingPlanDoc {
    pub prefix_7: String,
    pub ddd: String,
    pub carrier: String,
    pub line_type: LineType,
    pub is_corporate_pabx_range: bool,
    #[serde(with = "flexible_datetime")]
    pub updated_at: DateTime<Utc>,
}

// ============================================================================
// REST API Payloads & Responses
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchTelemetryResponse {
    pub accepted_ids: Vec<String>,
    pub quarantine_updates: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncPayloadQuery {
    pub ddd: Option<String>,
    pub since_timestamp: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncPayloadResponse {
    pub active_rules: Vec<ActiveRuleDoc>,
    pub prefix_quarantine: Vec<PrefixQuarantineDoc>,
    pub anatel_numbering_plan: Vec<AnatelNumberingPlanDoc>,
    pub server_timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedbackPatchRequest {
    pub user_feedback: UserFeedback,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedbackPatchResponse {
    pub id: String,
    pub user_feedback: UserFeedback,
    pub quarantine_removed_prefix: Option<String>,
    pub recalibrated_rules: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrefixStatItem {
    pub prefix_7: String,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CarrierStatItem {
    pub carrier: String,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CategoryStatItem {
    pub category: String,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HourlyHeatmapItem {
    pub hour: i32,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalyticsSummaryResponse {
    pub total_intercepted: i64,
    pub total_blocked: i64,
    pub avg_processing_latency_ms: f64,
    pub top_attacking_prefixes: Vec<PrefixStatItem>,
    pub carrier_distribution: Vec<CarrierStatItem>,
    pub category_distribution: Vec<CategoryStatItem>,
    pub hourly_heatmap: Vec<HourlyHeatmapItem>,
}
