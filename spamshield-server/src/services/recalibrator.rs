use crate::db::{doc_get_i32, AppDatabase};
use bson::{doc, DateTime as BsonDateTime};
use chrono::{Duration, Utc};

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
        .find_one_and_update(
            doc! { "_id": rule_id, "approved": true },
            doc! { "$set": { "approved": false, "consumed_at": BsonDateTime::now() } },
        )
        .await?;
    Ok(recorded.is_some())
}

/// Recalibrates rule weights and prefix quarantine when a call is marked as FALSE_POSITIVE.
/// Returns (removed_quarantine_prefix, recalibrated_rule_ids).
pub async fn handle_false_positive(
    db: &AppDatabase,
    telemetry_doc: &bson::Document,
    recorded_at: i64,
) -> Result<(Option<String>, Vec<String>), mongodb::error::Error> {
    let mut removed_prefix = None;
    let mut recalibrated_rules = Vec::new();

    if let Ok(call_details) = telemetry_doc.get_document("call_details") {
        if let Ok(prefix_7) = call_details.get_str("prefix_7") {
            crate::routes::rules::remove_quarantine(db, prefix_7, recorded_at).await?;
            if prefix_7.len() == 8 {
                crate::routes::rules::remove_quarantine(db, &prefix_7[..7], recorded_at).await?;
            }
            removed_prefix = Some(prefix_7.to_string());
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
                            let (mut false_positives, sample_count) =
                                rule_feedback_counts(db, rule_id).await?;
                            if telemetry_doc
                                .get_document("audit")
                                .ok()
                                .and_then(|a| a.get_str("user_feedback").ok())
                                != Some("FALSE_POSITIVE")
                            {
                                false_positives += 1;
                            }
                            if let Some(new_weight) = next_global_rule_weight(
                                current_weight,
                                false_positives,
                                sample_count,
                                true,
                            ) {
                                if !weight_change_approved(db, rule_id).await? {
                                    continue;
                                }
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
    recorded_at: i64,
) -> Result<Option<String>, mongodb::error::Error> {
    let Ok(details) = telemetry_doc.get_document("call_details") else {
        return Ok(None);
    };
    let prefix = details.get_str("prefix_7").unwrap_or_default();
    if prefix.is_empty() {
        return Ok(None);
    }
    let observed = chrono::TimeZone::timestamp_millis_opt(&Utc, recorded_at)
        .single()
        .unwrap_or_else(Utc::now);
    let q = crate::models::PrefixQuarantineDoc {
        prefix_7: prefix.to_string(),
        ddd: details.get_str("ddd").unwrap_or_default().to_string(),
        carrier_guess: details.get_str("carrier_guess").ok().map(str::to_string),
        distinct_numbers_seen: vec![details
            .get_str("normalized_number")
            .unwrap_or_default()
            .to_string()],
        hit_count: 1,
        penalty_weight: 80,
        reason: "USER_CONFIRMED_SPAM".into(),
        first_seen_at: observed,
        last_seen_at: observed,
        expires_at: observed + Duration::days(30),
    };
    crate::routes::rules::store_quarantine(db, &q, recorded_at).await?;
    Ok(Some(prefix.to_string()))
}
