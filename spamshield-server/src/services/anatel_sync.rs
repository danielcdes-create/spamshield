use crate::db::AppDatabase;
use crate::models::{AnatelNumberingPlanDoc, LineType};
use bson::{doc, DateTime as BsonDateTime};
use chrono::{Duration, Utc};
use futures::future::join_all;
use mongodb::options::UpdateOptions;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
pub struct AnatelCsvRow {
    pub ddd: String,
    pub prefix: String,
    pub carrier: String,
    pub line_type: String,
    #[serde(default)]
    pub is_corporate_pabx: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderSyncSummary {
    pub anatel_prefixes_synced: usize,
    pub blacklist_clusters_synced: usize,
    pub rules_synced: usize,
    pub sources: Vec<String>,
}

/// Curated Anatel Numbering Plan dataset covering MG (DDD 31), SP (DDD 11), RJ (DDD 21)
/// and major Brazilian VoIP/PABX wholesale trunks & mobile operator blocks (RN1).
pub const BUILTIN_ANATEL_PLAN_CSV: &str = "\
ddd,prefix,carrier,line_type,is_corporate_pabx
31,41011,CLARO FIXO / VOIP,FIXED_OR_VOIP,true
31,41012,CLARO FIXO / VOIP,FIXED_OR_VOIP,true
31,41013,CLARO FIXO / VOIP,FIXED_OR_VOIP,true
31,41014,CLARO FIXO / VOIP,FIXED_OR_VOIP,true
31,21081,AMERICANET VOIP,FIXED_OR_VOIP,true
31,25171,ALGAR TELECOM VOIP,FIXED_OR_VOIP,true
31,31911,DATORA VOIP,FIXED_OR_VOIP,true
31,40201,EMBRATEL PABX,FIXED_OR_VOIP,true
31,40621,VOXBRAS VOIP,FIXED_OR_VOIP,true
31,30031,TRONCO NACIONAL PABX,FIXED_OR_VOIP,true
31,98621,TIM S.A.,MOBILE,false
31,98633,TIM S.A.,MOBILE,false
31,98321,TIM S.A.,MOBILE,false
31,98586,TIM S.A.,MOBILE,false
31,97619,TIM S.A.,MOBILE,false
31,97100,TIM S.A.,MOBILE,false
31,99101,TIM S.A.,MOBILE,false
31,98780,CLARO S.A.,MOBILE,false
31,98401,CLARO S.A.,MOBILE,false
31,98801,CLARO S.A.,MOBILE,false
31,97301,CLARO S.A.,MOBILE,false
31,99901,VIVO S.A.,MOBILE,false
31,99801,VIVO S.A.,MOBILE,false
31,99701,VIVO S.A.,MOBILE,false
31,99601,VIVO S.A.,MOBILE,false
11,91086,TIM S.A.,MOBILE,false
11,93300,CLARO S.A.,MOBILE,false
11,30031,TRONCO NACIONAL PABX,FIXED_OR_VOIP,true
11,40031,AMERICANET VOIP,FIXED_OR_VOIP,true
11,40041,TRONCO CORPORATIVO,FIXED_OR_VOIP,true
11,41301,DATORA VOIP,FIXED_OR_VOIP,true
11,48581,TVN / VOIP DISPARO,FIXED_OR_VOIP,true
11,50391,NVOIP TRONCO MASSIVO,FIXED_OR_VOIP,true
21,40201,EMBRATEL PABX,FIXED_OR_VOIP,true
21,30031,TRONCO NACIONAL PABX,FIXED_OR_VOIP,true
";

/// Parses CSV content in parallel using Rayon and returns normalized `AnatelNumberingPlanDoc` items.
pub fn parse_anatel_csv_parallel(csv_data: &str) -> Vec<AnatelNumberingPlanDoc> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(true)
        .flexible(true)
        .trim(csv::Trim::All)
        .from_reader(csv_data.as_bytes());

    let raw_rows: Vec<AnatelCsvRow> = reader
        .deserialize::<AnatelCsvRow>()
        .filter_map(Result::ok)
        .collect();

    let now = Utc::now();

    raw_rows
        .into_par_iter()
        .filter_map(|row| {
            let ddd_digits: String = row.ddd.chars().filter(|c| c.is_ascii_digit()).collect();
            let prefix_digits: String =
                row.prefix.chars().filter(|c| c.is_ascii_digit()).collect();
            if ddd_digits.len() != 2 || prefix_digits.is_empty() {
                return None;
            }

            let prefix_7 = if prefix_digits.starts_with(&ddd_digits) && prefix_digits.len() >= 7 {
                prefix_digits[..7].to_string()
            } else {
                let combined = format!("{}{}", ddd_digits, prefix_digits);
                if combined.len() >= 7 {
                    combined[..7].to_string()
                } else {
                    combined
                }
            };

            let upper_type = row.line_type.to_uppercase();
            let line_type = if upper_type.contains("MOVEL")
                || upper_type.contains("MÓVEL")
                || upper_type.contains("MOBILE")
                || upper_type.contains("SMP")
            {
                LineType::MOBILE
            } else if upper_type.contains("FIX")
                || upper_type.contains("STFC")
                || upper_type.contains("VOIP")
            {
                LineType::FIXED_OR_VOIP
            } else if upper_type.contains("0800")
                || upper_type.contains("0300")
                || upper_type.contains("SPECIAL")
            {
                LineType::TOLL_FREE_OR_SPECIAL
            } else {
                LineType::UNKNOWN
            };

            let is_pabx = row.is_corporate_pabx
                || (line_type == LineType::FIXED_OR_VOIP
                    && (prefix_7.ends_with("4101")
                        || prefix_7.contains("4101")
                        || prefix_7.contains("3003")
                        || prefix_7.contains("4004")));

            Some(AnatelNumberingPlanDoc {
                prefix_7,
                ddd: ddd_digits,
                carrier: row.carrier.trim().to_uppercase(),
                line_type,
                is_corporate_pabx_range: is_pabx,
                updated_at: now,
            })
        })
        .collect()
}

/// Syncs parsed Anatel records into the `anatel_numbering_plan` collection in MongoDB concurrently.
pub async fn sync_anatel_records_to_db(
    db: &AppDatabase,
    records: &[AnatelNumberingPlanDoc],
) -> Result<usize, mongodb::error::Error> {
    let coll = db.anatel_numbering_plan();
    let now_bson = BsonDateTime::now();

    let futs = records.iter().map(|item| {
        let c = coll.clone();
        async move {
            c.update_one(
                doc! { "prefix_7": &item.prefix_7 },
                doc! {
                    "$set": {
                        "ddd": &item.ddd,
                        "carrier": &item.carrier,
                        "line_type": item.line_type.as_str(),
                        "is_corporate_pabx_range": item.is_corporate_pabx_range,
                        "updated_at": now_bson,
                    }
                },
            )
            .with_options(UpdateOptions::builder().upsert(true).build())
            .await
            .is_ok()
        }
    });

    let results = join_all(futs).await;
    Ok(results.into_iter().filter(|ok| *ok).count())
}

/// Syncs Anatel numbering plan + public Brazilian spammer blacklists (Ato 10.413 Anatel 0303,
/// Não Me Perturbe telemarketing trunks, and known robocall prefix clusters).
pub async fn sync_all_providers(
    db: &AppDatabase,
) -> Result<ProviderSyncSummary, mongodb::error::Error> {
    // 1. Parse and sync Anatel numbering plan (Rayon parallel parser)
    let parsed_anatel = parse_anatel_csv_parallel(BUILTIN_ANATEL_PLAN_CSV);
    let anatel_count = sync_anatel_records_to_db(db, &parsed_anatel).await?;

    // 2. Upsert Brazilian telemarketing & VoIP trunk regex rules (Anatel Ato 10.413 + PABX ranges)
    let now_bson = BsonDateTime::now();
    let provider_rules = vec![
        doc! {
            "rule_id": "ANATEL_ATO_10413_0303",
            "name": "Anatel Ato 10.413 — Telemarketing Obrigatório (0303)",
            "rule_type": "REGEX_MATCH",
            "pattern": "^(?:\\+55|0)?303\\d+$",
            "weight": 95_i32,
            "priority": 18_i32,
            "enabled": true,
            "updated_at": now_bson,
        },
        doc! {
            "rule_id": "BLACKLIST_SP_VOIP_ROBOCALL",
            "name": "Blacklist BR — Troncos VoIP Massivos SP (11 3003/4003/4858/5039)",
            "rule_type": "REGEX_MATCH",
            "pattern": "^(?:\\+55|0(?:\\d{2})?)?11(?:3003|4003|4130|4858|5039)\\d{4}$",
            "weight": 85_i32,
            "priority": 19_i32,
            "enabled": true,
            "updated_at": now_bson,
        },
        doc! {
            "rule_id": "BLACKLIST_MG_VOIP_PABX",
            "name": "Blacklist BR — Troncos VoIP/PABX MG (31 2108/2517/3191/4020/4062/4101)",
            "rule_type": "REGEX_MATCH",
            "pattern": "^(?:\\+55|0(?:\\d{2})?)?31(?:2108|2517|3191|4020|4062|4101)\\d{4}$",
            "weight": 80_i32,
            "priority": 20_i32,
            "enabled": true,
            "updated_at": now_bson,
        },
    ];

    let rules_coll = db.active_rules();
    let mut rules_synced = 0usize;
    for r in provider_rules {
        let rid = r.get_str("rule_id").unwrap_or_default().to_string();
        if rules_coll
            .update_one(doc! { "rule_id": rid }, doc! { "$set": r })
            .with_options(UpdateOptions::builder().upsert(true).build())
            .await
            .is_ok()
        {
            rules_synced += 1;
        }
    }

    // 3. Upsert known Brazilian spammer prefix clusters into prefix_quarantine
    let expires_at = BsonDateTime::from_chrono(Utc::now() + Duration::days(14));
    let known_clusters = vec![
        ("3141011", "31", "VoIP / Fixo", 75_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3141013", "31", "VoIP / Fixo", 70_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3198780", "31", "Claro", 65_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3197619", "31", "TIM", 65_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3198633", "31", "TIM", 65_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3198621", "31", "TIM", 60_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3198321", "31", "TIM", 60_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("3198586", "31", "TIM", 60_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("1191086", "11", "TIM", 70_i32, "ROBOCALL_PREFIX_CLUSTER"),
        ("1148581", "11", "VoIP / Fixo", 80_i32, " KNOWN_BLACKLIST"),
        ("1150391", "11", "VoIP / Fixo", 80_i32, "KNOWN_BLACKLIST"),
    ];

    let q_coll = db.prefix_quarantine();
    let mut blacklist_clusters_synced = 0usize;
    for (p7, ddd, carrier, weight, reason) in known_clusters {
        let res = q_coll
            .update_one(
                doc! { "prefix_7": p7 },
                doc! {
                    "$set": {
                        "ddd": ddd,
                        "carrier_guess": carrier,
                        "penalty_weight": weight,
                        "reason": reason.trim(),
                        "last_seen_at": now_bson,
                        "expires_at": expires_at,
                    },
                    "$setOnInsert": {
                        "distinct_numbers_seen": [],
                        "hit_count": 1_i32,
                        "first_seen_at": now_bson,
                    }
                },
            )
            .with_options(UpdateOptions::builder().upsert(true).build())
            .await;
        if res.is_ok() {
            blacklist_clusters_synced += 1;
        }
    }

    Ok(ProviderSyncSummary {
        anatel_prefixes_synced: anatel_count,
        blacklist_clusters_synced,
        rules_synced,
        sources: vec![
            "Plano de Numeração Anatel (STFC/SMP/RN1)".to_string(),
            "Anatel Ato nº 10.413 (Prefixo 0303 Telemarketing)".to_string(),
            "Blacklist Comunitária BR (Troncos VoIP & Clusters Robocall MG/SP)".to_string(),
        ],
    })
}
