use crate::db::{doc_get_i32, AppDatabase};
use bson::{doc, DateTime as BsonDateTime};
use chrono::{Duration, Utc};
use mongodb::options::UpdateOptions;
use tracing::info;

/// Global rule weight changes only when the sampled false-positive rate is
/// strictly above 5% and an administrative approval for that change is recorded.
/// One vote, or a rate at or below 5%, or a missing approval, leaves the weight unchanged.
pub fn next_global_rule_weight(
    current_weight: i32,
    false_positive_count: u64,
    sample_count: u64,
    admin_approved: bool,
) -> Option<i32> {
    if !admin_approved || sample_count == 0 {
        return None;
    }
    let rate = (false_positive_count as f64) / (sample_count as f64);
    if rate > 0.05 && current_weight > 15 {
        Some((current_weight - 5).max(15))
    } else {
        None
    }
}

async fn rule_feedback_counts(
    db: &AppDatabase,
    rule_id: &str,
) -> Result<(u64, u64), mongodb::error::Error> {
    let sample = db
        .call_telemetry()
        .count_documents(doc! { "decision_vector.triggered_rules.rule_id": rule_id })
        .await?;
    let false_positives = db
        .call_telemetry()
        .count_documents(doc! {
            "decision_vector.triggered_rules.rule_id": rule_id,
            "audit.user_feedback": "FALSE_POSITIVE"
        })
        .await?;
    Ok((false_positives, sample))
}

async fn weight_change_approved(
    db: &AppDatabase,
    rule_id: &str,
) -> Result<bool, mongodb::error::Error> {
    let recorded = db
        .rule_weight_approvals()
        .find_one(doc! { "_id": rule_id, "approved": true })
        .await?;
    Ok(recorded.is_some())
}

/// Recalibrates rule weights and prefix quarantine when a call is marked as FALSE_POSITIVE.
/// Returns (removed_quarantine_prefix, recalibrated_rule_ids).
pub async fn handle_false_positive(
    db: &AppDatabase,
    telemetry_doc: &bson::Document,
) -> Result<(Option<String>, Vec<String>), mongodb::error::Error> {
    let mut removed_prefix = None;
    let mut recalibrated_rules = Vec::new();

    if let Ok(call_details) = telemetry_doc.get_document("call_details") {
        if let Ok(prefix_7) = call_details.get_str("prefix_7") {
            let del_res = db
                .prefix_quarantine()
                .delete_one(doc! { "prefix_7": prefix_7 })
                .await?;
            if del_res.deleted_count > 0 {
                info!(
                    "Removed prefix_7 '{}' from prefix_quarantine due to FALSE_POSITIVE feedback",
                    prefix_7
                );
                removed_prefix = Some(prefix_7.to_string());
            }
        }
    }

    if let Ok(decision_vector) = telemetry_doc.get_document("decision_vector") {
        if let Ok(triggered_rules) = decision_vector.get_array("triggered_rules") {
            for rule_bson in triggered_rules {
                if let Some(rule_obj) = rule_bson.as_document() {
                    if let Ok(rule_id) = rule_obj.get_str("rule_id") {
                        // Do not recalibrate short-circuit allow rules or basic NOT_IN_CONTACTS
                        if matches!(
                            rule_id,
                            "CONTACT_WHITELIST"
                                | "EMERGENCY_REPEATED_CALL"
                                | "OUTBOUND_GRACE_PERIOD"
                                | "NOT_IN_CONTACTS"
                        ) {
                            continue;
                        }

                        if let Some(active_rule) = db
                            .active_rules()
                            .find_one(doc! { "rule_id": rule_id })
                            .await?
                        {
                            let current_weight = doc_get_i32(&active_rule, "weight", 50);
                            let (false_positives, sample_count) =
                                rule_feedback_counts(db, rule_id).await?;
                            let approved = weight_change_approved(db, rule_id).await?;
                            if let Some(new_weight) = next_global_rule_weight(
                                current_weight,
                                false_positives,
                                sample_count,
                                approved,
                            ) {
                                db.active_rules()
                                    .update_one(
                                        doc! { "rule_id": rule_id },
                                        doc! {
                                            "$set": {
                                                "weight": new_weight,
                                                "updated_at": BsonDateTime::now(),
                                            }
                                        },
                                    )
                                    .await?;
                                recalibrated_rules.push(rule_id.to_string());
                            }
                        }
                    }
                }
            }
        }
    }

    Ok((removed_prefix, recalibrated_rules))
}

/// Reinforces prefix quarantine when user confirms spam (`CONFIRMED_SPAM`).
pub async fn handle_confirmed_spam(
    db: &AppDatabase,
    telemetry_doc: &bson::Document,
) -> Result<Option<String>, mongodb::error::Error> {
    if let Ok(call_details) = telemetry_doc.get_document("call_details") {
        let prefix_7 = call_details.get_str("prefix_7").unwrap_or_default();
        if prefix_7.is_empty() {
            return Ok(None);
        }
        let ddd = call_details.get_str("ddd").unwrap_or("");
        let normalized_number = call_details.get_str("normalized_number").unwrap_or("");
        let carrier_guess = call_details.get_str("carrier_guess").unwrap_or("Desconhecida");

        let now = BsonDateTime::now();
        let expires_at = BsonDateTime::from_chrono(Utc::now() + Duration::days(30));

        db.prefix_quarantine()
            .update_one(
                doc! { "prefix_7": prefix_7 },
                doc! {
                    "$inc": { "hit_count": 1_i32 },
                    "$addToSet": { "distinct_numbers_seen": normalized_number },
                    "$set": {
                        "last_seen_at": now,
                        "expires_at": expires_at,
                        "penalty_weight": 80_i32,
                    },
                    "$setOnInsert": {
                        "ddd": ddd,
                        "carrier_guess": carrier_guess,
                        "reason": "USER_CONFIRMED_SPAM",
                        "first_seen_at": now,
                    }
                },
            )
            .with_options(UpdateOptions::builder().upsert(true).build())
            .await?;

        return Ok(Some(prefix_7.to_string()));
    }
    Ok(None)
}
