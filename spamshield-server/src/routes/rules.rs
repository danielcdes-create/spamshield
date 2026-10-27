use super::AppState;
use crate::db::{parse_active_rule, parse_anatel_doc, parse_prefix_quarantine};
use crate::models::{
    ActiveRuleDoc, AnatelNumberingPlanDoc, PrefixQuarantineDoc, SyncPayloadQuery,
    SyncPayloadResponse,
};
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::Utc;
use futures::TryStreamExt;
use mongodb::options::{FindOptions, UpdateOptions};
use serde_json::{json, Value};

/// GET /api/v1/sync/payload?ddd=31&since_timestamp=1700000000
/// Returns active rules (`enabled: true`), active `prefix_quarantine`, and `anatel_numbering_plan`.
pub async fn get_sync_payload(
    State(state): State<AppState>,
    Query(query): Query<SyncPayloadQuery>,
) -> Result<Json<SyncPayloadResponse>, (StatusCode, String)> {
    let db = &state.db;
    // 1. Active rules (enabled: true) sorted by priority ascending
    let rules_cursor = db
        .active_rules()
        .find(doc! { "enabled": true })
        .with_options(FindOptions::builder().sort(doc! { "priority": 1 }).build())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let rules_docs: Vec<Document> = rules_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let active_rules: Vec<ActiveRuleDoc> =
        rules_docs.iter().filter_map(parse_active_rule).collect();

    // 2. Active prefix_quarantine (expires_at > now)
    let now_bson = BsonDateTime::now();
    let quarantine_cursor = db
        .prefix_quarantine()
        .find(doc! { "expires_at": { "$gt": now_bson } })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let quarantine_docs: Vec<Document> = quarantine_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let prefix_quarantine: Vec<PrefixQuarantineDoc> = quarantine_docs
        .iter()
        .filter_map(parse_prefix_quarantine)
        .collect();

    // 3. Anatel numbering plan: return user's DDD + all corporate PABX/VoIP ranges across Brazil
    let anatel_filter = if let Some(ref ddd) = query.ddd {
        let trimmed = ddd.trim();
        if !trimmed.is_empty() {
            doc! {
                "$or": [
                    { "ddd": trimmed },
                    { "is_corporate_pabx_range": true },
                    { "ddd": "11" },
                    { "ddd": "21" }
                ]
            }
        } else {
            Document::new()
        }
    } else {
        Document::new()
    };

    let anatel_cursor = db
        .anatel_numbering_plan()
        .find(anatel_filter)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let anatel_docs: Vec<Document> = anatel_cursor
        .try_collect()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let anatel_numbering_plan: Vec<AnatelNumberingPlanDoc> =
        anatel_docs.iter().filter_map(parse_anatel_doc).collect();

    Ok(Json(SyncPayloadResponse {
        active_rules,
        prefix_quarantine,
        anatel_numbering_plan,
        server_timestamp: Utc::now().timestamp_millis(),
    }))
}

/// POST /api/v1/rules
pub async fn upsert_active_rule(
    State(state): State<AppState>,
    Json(rule): Json<ActiveRuleDoc>,
) -> Result<Json<ActiveRuleDoc>, (StatusCode, String)> {
    let db = &state.db;
    let pattern_bson = match &rule.pattern {
        Some(p) => Bson::String(p.clone()),
        None => Bson::Null,
    };

    db.active_rules()
        .update_one(
            doc! { "rule_id": &rule.rule_id },
            doc! {
                "$set": {
                    "name": &rule.name,
                    "rule_type": &rule.rule_type,
                    "pattern": pattern_bson,
                    "weight": rule.weight,
                    "priority": rule.priority,
                    "enabled": rule.enabled,
                    "updated_at": BsonDateTime::now(),
                }
            },
        )
        .with_options(UpdateOptions::builder().upsert(true).build())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(rule))
}

/// POST /api/v1/quarantine
pub async fn upsert_quarantine(
    State(state): State<AppState>,
    Json(q): Json<PrefixQuarantineDoc>,
) -> Result<Json<PrefixQuarantineDoc>, (StatusCode, String)> {
    let db = &state.db;
    let (filter, update) = quarantine_upsert_parts(&q);
    db.prefix_quarantine()
        .update_one(filter, update)
        .with_options(UpdateOptions::builder().upsert(true).build())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(q))
}

fn quarantine_upsert_parts(q: &PrefixQuarantineDoc) -> (Document, Document) {
    let carrier_bson = match &q.carrier_guess {
        Some(c) => Bson::String(c.clone()),
        None => Bson::Null,
    };
    (
        doc! { "prefix_7": &q.prefix_7 },
        doc! {
            "$set": {
                "ddd": &q.ddd,
                "carrier_guess": carrier_bson,
                "hit_count": q.hit_count,
                "penalty_weight": q.penalty_weight,
                "reason": &q.reason,
                "last_seen_at": BsonDateTime::from_chrono(q.last_seen_at),
                "expires_at": BsonDateTime::from_chrono(q.expires_at),
            },
            "$setOnInsert": {
                "distinct_numbers_seen": &q.distinct_numbers_seen,
                "first_seen_at": BsonDateTime::from_chrono(q.first_seen_at),
            }
        },
    )
}

/// POST /api/v1/quarantine/batch
/// One bulk write for the whole list.
pub async fn upsert_quarantine_batch(
    State(state): State<AppState>,
    Json(items): Json<Vec<PrefixQuarantineDoc>>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let updates = items
        .iter()
        .map(|q| {
            let (filter, update) = quarantine_upsert_parts(q);
            (filter, update, true)
        })
        .collect();
    let upserted = state
        .db
        .bulk_update_ones("prefix_quarantine", updates)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(json!({ "upserted": upserted, "received": items.len() })))
}

/// DELETE /api/v1/quarantine/:prefix7
pub async fn delete_quarantine(
    State(state): State<AppState>,
    Path(prefix7): Path<String>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let db = &state.db;
    let res = db
        .prefix_quarantine()
        .delete_one(doc! { "prefix_7": &prefix7 })
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(json!({
        "prefix_7": prefix7,
        "deleted": res.deleted_count > 0
    })))
}
