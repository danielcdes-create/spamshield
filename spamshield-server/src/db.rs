use crate::config::AppConfig;
use crate::models::{ActiveRuleDoc, AnatelNumberingPlanDoc, LineType, PrefixQuarantineDoc};
use bson::{doc, Bson, DateTime as BsonDateTime, Document};
use chrono::{DateTime, Duration, TimeZone, Utc};
use mongodb::{
    options::{ClientOptions, CreateIndexOptions, IndexOptions, UpdateOptions},
    Client, Collection, Database, IndexModel,
};
use std::time::Duration as StdDuration;
use tracing::{info, warn};

#[derive(Clone)]
pub struct AppDatabase {
    pub client: Client,
    pub db: Database,
}

impl AppDatabase {
    pub async fn connect(config: &AppConfig) -> Result<Self, mongodb::error::Error> {
        let client = match Self::try_connect_uri(&config.mongodb_uri).await {
            Ok(c) => {
                info!("Connected to MongoDB Atlas via primary URI");
                c
            }
            Err(primary_err) => {
                warn!(
                    "Primary MongoDB URI connection failed ({}). Trying direct replica-set fallback URI...",
                    primary_err
                );
                if let Some(direct_uri) = &config.mongodb_direct_uri {
                    let c = Self::try_connect_uri(direct_uri).await?;
                    info!("Connected to MongoDB Atlas via direct replica-set URI");
                    c
                } else {
                    return Err(primary_err);
                }
            }
        };

        let db = client.database(&config.mongodb_db_name);
        Ok(Self { client, db })
    }

    async fn try_connect_uri(uri: &str) -> Result<Client, mongodb::error::Error> {
        let mut opts = ClientOptions::parse(uri).await?;
        opts.server_selection_timeout = Some(StdDuration::from_secs(8));
        opts.connect_timeout = Some(StdDuration::from_secs(8));
        let client = Client::with_options(opts)?;
        client
            .database("admin")
            .run_command(doc! { "ping": 1 })
            .await?;
        Ok(client)
    }

    pub fn call_telemetry(&self) -> Collection<Document> {
        self.db.collection::<Document>("call_telemetry")
    }

    pub fn active_rules(&self) -> Collection<Document> {
        self.db.collection::<Document>("active_rules")
    }

    pub fn prefix_quarantine(&self) -> Collection<Document> {
        self.db.collection::<Document>("prefix_quarantine")
    }

    pub fn anatel_numbering_plan(&self) -> Collection<Document> {
        self.db.collection::<Document>("anatel_numbering_plan")
    }

    /// Executes the exact initialization from `init_mongodb.mongodb.js`:
    /// creates collections with JSON schema validation, creates all indexes (including TTL),
    /// and seeds the initial rules, quarantine clusters, and Anatel numbering plan.
    pub async fn init_and_seed(&self) -> Result<(), mongodb::error::Error> {
        info!("Running MongoDB initialization & seed for spam_shield_db...");

        let existing_collections = self.db.list_collection_names().await?;

        // ====================================================================
        // 1. call_telemetry collection + JSON Schema validator + indexes
        // ====================================================================
        let telemetry_validator = doc! {
            "$jsonSchema": {
                "bsonType": "object",
                "required": ["_id", "timestamp", "device_id", "call_details", "decision_vector", "audit"],
                "properties": {
                    "_id": { "bsonType": "string", "description": "UUID v4 gerado no Android" },
                    "timestamp": { "bsonType": "date" },
                    "device_id": { "bsonType": "string" },
                    "call_details": {
                        "bsonType": "object",
                        "required": ["raw_number", "normalized_number", "ddd", "prefix_7", "line_type", "stir_shaken_status"],
                        "properties": {
                            "raw_number": { "bsonType": "string" },
                            "normalized_number": { "bsonType": "string" },
                            "ddd": { "bsonType": "string" },
                            "prefix_7": { "bsonType": "string" },
                            "carrier_guess": { "bsonType": ["string", "null"] },
                            "line_type": { "enum": ["MOBILE", "FIXED_OR_VOIP", "TOLL_FREE_OR_SPECIAL", "UNKNOWN"] },
                            "stir_shaken_status": { "enum": ["PASSED", "FAILED", "NOT_VERIFIED"] }
                        }
                    },
                    "decision_vector": {
                        "bsonType": "object",
                        "required": ["final_score", "threshold_applied", "outcome", "category", "processing_latency_ms", "triggered_rules"],
                        "properties": {
                            "final_score": { "bsonType": "int" },
                            "threshold_applied": { "bsonType": "int" },
                            "outcome": {
                                "enum": [
                                    "BLOCKED",
                                    "SHADOW_BLOCKED",
                                    "ALLOWED_CONTACT",
                                    "ALLOWED_EMERGENCY_REPEAT",
                                    "ALLOWED_OUTBOUND_GRACE",
                                    "ALLOWED_LOW_SCORE"
                                ]
                            },
                            "category": {
                                "enum": [
                                    "ROBOCALL_PREFIX_CLUSTER",
                                    "SPOOFING_STIR_FAILED",
                                    "KNOWN_BLACKLIST",
                                    "PING_CALL_ROBOCALL",
                                    "LEGITIMATE",
                                    "UNKNOWN_UNVERIFIED"
                                ]
                            },
                            "processing_latency_ms": { "bsonType": "double" },
                            "shadow_mode_active": { "bsonType": "bool" },
                            "call_duration_sec": { "bsonType": ["int", "null"] },
                            "triggered_rules": {
                                "bsonType": "array",
                                "items": {
                                    "bsonType": "object",
                                    "required": ["rule_id", "weight"],
                                    "properties": {
                                        "rule_id": { "bsonType": "string" },
                                        "weight": { "bsonType": "int" },
                                        "meta": { "bsonType": ["string", "null"] }
                                    }
                                }
                            }
                        }
                    },
                    "audit": {
                        "bsonType": "object",
                        "required": ["user_feedback", "synced_at"],
                        "properties": {
                            "user_feedback": { "enum": ["UNRATED", "CONFIRMED_SPAM", "FALSE_POSITIVE"] },
                            "synced_at": { "bsonType": "date" }
                        }
                    }
                }
            }
        };

        if !existing_collections.contains(&"call_telemetry".to_string()) {
            self.db
                .run_command(doc! {
                    "create": "call_telemetry",
                    "validator": telemetry_validator,
                })
                .await?;
            info!("Created collection 'call_telemetry' with JSON Schema validator");
        } else {
            let _ = self
                .db
                .run_command(doc! {
                    "collMod": "call_telemetry",
                    "validator": telemetry_validator,
                })
                .await;
        }

        let telemetry_coll = self.call_telemetry();
        telemetry_coll
            .create_indexes(
                vec![
                    IndexModel::builder().keys(doc! { "timestamp": -1 }).build(),
                    IndexModel::builder()
                        .keys(doc! { "call_details.prefix_7": 1, "timestamp": -1 })
                        .build(),
                    IndexModel::builder()
                        .keys(doc! { "decision_vector.outcome": 1, "timestamp": -1 })
                        .build(),
                    IndexModel::builder()
                        .keys(doc! { "decision_vector.category": 1, "timestamp": -1 })
                        .build(),
                    IndexModel::builder()
                        .keys(doc! { "audit.user_feedback": 1 })
                        .build(),
                ],
            )
            .with_options(CreateIndexOptions::default())
            .await?;

        // ====================================================================
        // 2. active_rules collection + indexes + seed
        // ====================================================================
        if !existing_collections.contains(&"active_rules".to_string()) {
            self.db.create_collection("active_rules").await?;
        }
        let rules_coll = self.active_rules();
        rules_coll
            .create_indexes(vec![
                IndexModel::builder()
                    .keys(doc! { "rule_id": 1 })
                    .options(IndexOptions::builder().unique(true).build())
                    .build(),
                IndexModel::builder()
                    .keys(doc! { "enabled": 1, "priority": 1 })
                    .build(),
            ])
            .await?;

        let now_bson = BsonDateTime::now();
        let initial_rules = vec![
            doc! {
                "rule_id": "CONTACT_WHITELIST",
                "name": "Número Salvo na Agenda",
                "rule_type": "SHORT_CIRCUIT_ALLOW",
                "pattern": Bson::Null,
                "weight": -1000_i32,
                "priority": 1_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "EMERGENCY_REPEATED_CALL",
                "name": "Repetição de Emergência (2x em 3 min)",
                "rule_type": "SHORT_CIRCUIT_ALLOW",
                "pattern": Bson::Null,
                "weight": -500_i32,
                "priority": 2_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "OUTBOUND_GRACE_PERIOD",
                "name": "Imunidade de Saída (Retorno de PBX em até 2h)",
                "rule_type": "SHORT_CIRCUIT_ALLOW",
                "pattern": Bson::Null,
                "weight": -500_i32,
                "priority": 3_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "STIR_SHAKEN_FAILED",
                "name": "Falha de Autenticação STIR/SHAKEN (Spoofing)",
                "rule_type": "HEURISTIC_WEIGHT",
                "pattern": Bson::Null,
                "weight": 85_i32,
                "priority": 10_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "STIR_SHAKEN_UNVERIFIED",
                "name": "Chamada Sem Assinatura STIR/SHAKEN",
                "rule_type": "HEURISTIC_WEIGHT",
                "pattern": Bson::Null,
                "weight": 20_i32,
                "priority": 11_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "NOT_IN_CONTACTS",
                "name": "Número Desconhecido (Fora da Agenda)",
                "rule_type": "HEURISTIC_WEIGHT",
                "pattern": Bson::Null,
                "weight": 25_i32,
                "priority": 12_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "PREFIX_NEIGHBORHOOD_BURST",
                "name": "Ataque de Vizinhança (Mesmo Prefixo 7 Dígitos nas últimas 24h)",
                "rule_type": "HEURISTIC_WEIGHT",
                "pattern": Bson::Null,
                "weight": 60_i32,
                "priority": 13_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "PING_CALL_PENALTY",
                "name": "Histórico de Chamada Muda (< 4s)",
                "rule_type": "HEURISTIC_WEIGHT",
                "pattern": Bson::Null,
                "weight": 55_i32,
                "priority": 14_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "REGEX_MG_VOIP_TRUNKS",
                "name": "Troncos VoIP/Massivos Detectados em MG (31 4101-XXXX)",
                "rule_type": "REGEX_MATCH",
                "pattern": "^(?:\\+55|0)?314101\\d{4}$",
                "weight": 80_i32,
                "priority": 20_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
            doc! {
                "rule_id": "REGEX_TELEMARKETING_NATIONAL",
                "name": "Prefixos Nacionais de Telemarketing (0300, 0303, 4004)",
                "rule_type": "REGEX_MATCH",
                "pattern": "^(?:\\+55|0)?(?:300|303|4004)\\d+$",
                "weight": 90_i32,
                "priority": 21_i32,
                "enabled": true,
                "updated_at": now_bson,
            },
        ];

        for rule_doc in initial_rules {
            let rule_id = rule_doc.get_str("rule_id").unwrap_or_default().to_string();
            rules_coll
                .update_one(
                    doc! { "rule_id": &rule_id },
                    doc! { "$setOnInsert": rule_doc },
                )
                .with_options(UpdateOptions::builder().upsert(true).build())
                .await?;
        }

        // ====================================================================
        // 3. prefix_quarantine collection + TTL index + seed
        // ====================================================================
        if !existing_collections.contains(&"prefix_quarantine".to_string()) {
            self.db.create_collection("prefix_quarantine").await?;
        }
        let quarantine_coll = self.prefix_quarantine();
        quarantine_coll
            .create_indexes(vec![
                IndexModel::builder()
                    .keys(doc! { "prefix_7": 1 })
                    .options(IndexOptions::builder().unique(true).build())
                    .build(),
                IndexModel::builder()
                    .keys(doc! { "expires_at": 1 })
                    .options(
                        IndexOptions::builder()
                            .expire_after(StdDuration::from_secs(0))
                            .build(),
                    )
                    .build(),
            ])
            .await?;

        let seven_days = BsonDateTime::from_chrono(Utc::now() + Duration::days(7));
        let initial_quarantine = vec![
            doc! {
                "prefix_7": "3141011",
                "ddd": "31",
                "carrier_guess": "VoIP",
                "distinct_numbers_seen": ["3141011230"],
                "hit_count": 2_i32,
                "penalty_weight": 70_i32,
                "reason": "ROBOCALL_PREFIX_CLUSTER",
                "first_seen_at": now_bson,
                "last_seen_at": now_bson,
                "expires_at": seven_days,
            },
            doc! {
                "prefix_7": "3141013",
                "ddd": "31",
                "carrier_guess": "VoIP",
                "distinct_numbers_seen": ["3141013701"],
                "hit_count": 1_i32,
                "penalty_weight": 60_i32,
                "reason": "ROBOCALL_PREFIX_CLUSTER",
                "first_seen_at": now_bson,
                "last_seen_at": now_bson,
                "expires_at": seven_days,
            },
            doc! {
                "prefix_7": "3198621",
                "ddd": "31",
                "carrier_guess": "TIM",
                "distinct_numbers_seen": ["3198621040"],
                "hit_count": 1_i32,
                "penalty_weight": 50_i32,
                "reason": "SPOOFING_OR_ROBOCALL_MOBILE",
                "first_seen_at": now_bson,
                "last_seen_at": now_bson,
                "expires_at": seven_days,
            },
            doc! {
                "prefix_7": "3198321",
                "ddd": "31",
                "carrier_guess": "TIM",
                "distinct_numbers_seen": ["31983211420"],
                "hit_count": 1_i32,
                "penalty_weight": 50_i32,
                "reason": "SPOOFING_OR_ROBOCALL_MOBILE",
                "first_seen_at": now_bson,
                "last_seen_at": now_bson,
                "expires_at": seven_days,
            },
            doc! {
                "prefix_7": "3198586",
                "ddd": "31",
                "carrier_guess": "TIM",
                "distinct_numbers_seen": ["31985862992"],
                "hit_count": 1_i32,
                "penalty_weight": 50_i32,
                "reason": "SPOOFING_OR_ROBOCALL_MOBILE",
                "first_seen_at": now_bson,
                "last_seen_at": now_bson,
                "expires_at": seven_days,
            },
        ];

        for q_doc in initial_quarantine {
            let prefix = q_doc.get_str("prefix_7").unwrap_or_default().to_string();
            quarantine_coll
                .update_one(
                    doc! { "prefix_7": &prefix },
                    doc! { "$setOnInsert": q_doc },
                )
                .with_options(UpdateOptions::builder().upsert(true).build())
                .await?;
        }

        // ====================================================================
        // 4. anatel_numbering_plan collection + indexes + seed
        // ====================================================================
        if !existing_collections.contains(&"anatel_numbering_plan".to_string()) {
            self.db.create_collection("anatel_numbering_plan").await?;
        }
        let anatel_coll = self.anatel_numbering_plan();
        anatel_coll
            .create_indexes(vec![
                IndexModel::builder()
                    .keys(doc! { "prefix_7": 1 })
                    .options(IndexOptions::builder().unique(true).build())
                    .build(),
                IndexModel::builder()
                    .keys(doc! { "ddd": 1, "line_type": 1 })
                    .build(),
            ])
            .await?;

        let initial_anatel = vec![
            doc! {
                "prefix_7": "3141011",
                "ddd": "31",
                "carrier": "VoIP",
                "line_type": "FIXED_OR_VOIP",
                "is_corporate_pabx_range": true,
                "updated_at": now_bson,
            },
            doc! {
                "prefix_7": "3141013",
                "ddd": "31",
                "carrier": "VoIP",
                "line_type": "FIXED_OR_VOIP",
                "is_corporate_pabx_range": true,
                "updated_at": now_bson,
            },
            doc! {
                "prefix_7": "3198621",
                "ddd": "31",
                "carrier": "TIM",
                "line_type": "MOBILE",
                "is_corporate_pabx_range": false,
                "updated_at": now_bson,
            },
            doc! {
                "prefix_7": "3198321",
                "ddd": "31",
                "carrier": "TIM",
                "line_type": "MOBILE",
                "is_corporate_pabx_range": false,
                "updated_at": now_bson,
            },
            doc! {
                "prefix_7": "3198586",
                "ddd": "31",
                "carrier": "TIM",
                "line_type": "MOBILE",
                "is_corporate_pabx_range": false,
                "updated_at": now_bson,
            },
        ];

        for a_doc in initial_anatel {
            let prefix = a_doc.get_str("prefix_7").unwrap_or_default().to_string();
            anatel_coll
                .update_one(
                    doc! { "prefix_7": &prefix },
                    doc! { "$setOnInsert": a_doc },
                )
                .with_options(UpdateOptions::builder().upsert(true).build())
                .await?;
        }

        self.migrate_historical_records().await?;

        info!("MongoDB initialization & seed completed successfully.");
        Ok(())
    }

    /// Retroactively normalizes and re-classifies all historical records in MongoDB Atlas
    /// (`call_telemetry`, `prefix_quarantine`, and `anatel_numbering_plan`):
    /// - Splits "VoIP / Fixo" into distinct "VoIP" vs "Fixo" categories.
    /// - Canonicalizes "TIM S.A." -> "TIM", "CLARO S.A." -> "Claro", "VIVO S.A." -> "Vivo".
    /// - Re-attributes MVNO/Claro mobile blocks (937..939, 980) to "Claro".
    /// - Fixes 0411140043535 (VoIP), 004131939995971 (TIM), 03110315 (VoIP), 031988644641 (BLOCKED),
    ///   and revokes improper ALLOWED_EMERGENCY_REPEAT on non-mobile / STIR_FAILED / quarantined numbers.
    pub async fn migrate_historical_records(&self) -> Result<(), mongodb::error::Error> {
        let telemetry_coll = self.call_telemetry();
        let quarantine_coll = self.prefix_quarantine();
        let anatel_coll = self.anatel_numbering_plan();

        // 1. Canonicalize legacy carrier names in anatel_numbering_plan
        let _ = anatel_coll
            .update_many(
                doc! { "carrier": "TIM S.A." },
                doc! { "$set": { "carrier": "TIM" } },
            )
            .await;
        let _ = anatel_coll
            .update_many(
                doc! { "carrier": "CLARO S.A.", "line_type": "MOBILE" },
                doc! { "$set": { "carrier": "Claro" } },
            )
            .await;
        let _ = anatel_coll
            .update_many(
                doc! { "carrier": "VIVO S.A.", "line_type": "MOBILE" },
                doc! { "$set": { "carrier": "Vivo" } },
            )
            .await;
        let _ = anatel_coll
            .update_many(
                doc! { "line_type": "FIXED_OR_VOIP", "is_corporate_pabx_range": true },
                doc! { "$set": { "carrier": "VoIP" } },
            )
            .await;

        // 2. Canonicalize prefix_quarantine
        let _ = quarantine_coll
            .update_many(
                doc! { "carrier_guess": "TIM S.A." },
                doc! { "$set": { "carrier_guess": "TIM" } },
            )
            .await;
        let _ = quarantine_coll
            .update_many(
                doc! { "prefix_7": { "$regex": "^..9(37|38|39|80)" } },
                doc! { "$set": { "carrier_guess": "Claro" } },
            )
            .await;
        let _ = quarantine_coll
            .update_many(
                doc! {
                    "$or": [
                        { "carrier_guess": "VoIP / Fixo" },
                        { "carrier_guess": "Claro Fixo / VoIP" },
                    ],
                    "prefix_7": { "$regex": "^..3[2-9]" }
                },
                doc! { "$set": { "carrier_guess": "Fixo" } },
            )
            .await;
        let _ = quarantine_coll
            .update_many(
                doc! {
                    "$or": [
                        { "carrier_guess": "VoIP / Fixo" },
                        { "carrier_guess": "Claro Fixo / VoIP" },
                    ]
                },
                doc! { "$set": { "carrier_guess": "VoIP" } },
            )
            .await;

        // 3. Canonicalize call_telemetry
        // 3a. "TIM S.A." -> "TIM"
        let _ = telemetry_coll
            .update_many(
                doc! { "call_details.carrier_guess": "TIM S.A." },
                doc! { "$set": { "call_details.carrier_guess": "TIM" } },
            )
            .await;

        // 3b. Mobile MVNO/Claro blocks 937..939 and 980 -> "Claro"
        let _ = telemetry_coll
            .update_many(
                doc! {
                    "call_details.line_type": "MOBILE",
                    "call_details.prefix_7": { "$regex": "^..9(37|38|39|80)" }
                },
                doc! { "$set": { "call_details.carrier_guess": "Claro" } },
            )
            .await;

        // 3c. Separate Fixo (STFC geographic 32x..39x) vs VoIP (2xx, 30x, 31x, 4xx, 5xx, 0300/0303/0800/4004)
        let _ = telemetry_coll
            .update_many(
                doc! {
                    "call_details.line_type": "FIXED_OR_VOIP",
                    "call_details.prefix_7": { "$regex": "^..3[2-9]" }
                },
                doc! { "$set": { "call_details.carrier_guess": "Fixo" } },
            )
            .await;
        let _ = telemetry_coll
            .update_many(
                doc! {
                    "$or": [
                        {
                            "call_details.line_type": "FIXED_OR_VOIP",
                            "call_details.prefix_7": { "$not": { "$regex": "^..3[2-9]" } }
                        },
                        { "call_details.line_type": "TOLL_FREE_OR_SPECIAL" }
                    ]
                },
                doc! { "$set": { "call_details.carrier_guess": "VoIP" } },
            )
            .await;

        // 3d. Fix specific edge numbers (004131939995971, 03110315, 005, 031988644641)
        let _ = telemetry_coll
            .update_many(
                doc! { "call_details.raw_number": "004131939995971" },
                doc! {
                    "$set": {
                        "call_details.normalized_number": "+5531939995971",
                        "call_details.ddd": "31",
                        "call_details.prefix_7": "3193999",
                        "call_details.line_type": "MOBILE",
                        "call_details.carrier_guess": "TIM",
                        "decision_vector.outcome": "BLOCKED",
                        "decision_vector.category": "ROBOCALL_PREFIX_CLUSTER",
                        "decision_vector.final_score": 105_i32,
                    }
                },
            )
            .await;

        let _ = telemetry_coll
            .update_many(
                doc! { "call_details.raw_number": "03110315" },
                doc! {
                    "$set": {
                        "call_details.normalized_number": "+553110315",
                        "call_details.ddd": "31",
                        "call_details.prefix_7": "3110315",
                        "call_details.line_type": "TOLL_FREE_OR_SPECIAL",
                        "call_details.carrier_guess": "VoIP",
                        "decision_vector.outcome": "BLOCKED",
                        "decision_vector.category": "ROBOCALL_PREFIX_CLUSTER",
                        "decision_vector.final_score": 105_i32,
                    }
                },
            )
            .await;

        let _ = telemetry_coll
            .update_many(
                doc! { "call_details.raw_number": "005" },
                doc! {
                    "$set": {
                        "call_details.line_type": "FIXED_OR_VOIP",
                        "call_details.carrier_guess": "VoIP",
                    }
                },
            )
            .await;

        let _ = telemetry_coll
            .update_many(
                doc! {
                    "call_details.raw_number": "031988644641",
                    "decision_vector.outcome": "ALLOWED_LOW_SCORE"
                },
                doc! {
                    "$set": {
                        "decision_vector.outcome": "BLOCKED",
                        "decision_vector.category": "ROBOCALL_PREFIX_CLUSTER",
                        "decision_vector.final_score": 140_i32,
                    }
                },
            )
            .await;

        // 3e. Revoke ALLOWED_EMERGENCY_REPEAT on non-mobile, STIR_FAILED, leading-zero, or quarantined prefixes
        let _ = telemetry_coll
            .update_many(
                doc! {
                    "decision_vector.outcome": "ALLOWED_EMERGENCY_REPEAT",
                    "$or": [
                        { "call_details.line_type": { "$ne": "MOBILE" } },
                        { "call_details.stir_shaken_status": "FAILED" },
                        { "call_details.raw_number": { "$regex": "^0" } },
                        { "call_details.prefix_7": "3131920" }
                    ]
                },
                doc! {
                    "$set": {
                        "decision_vector.outcome": "BLOCKED",
                        "decision_vector.category": "ROBOCALL_PREFIX_CLUSTER",
                        "decision_vector.final_score": 110_i32,
                    }
                },
            )
            .await;

        Ok(())
    }
}

pub fn doc_get_datetime(doc: &Document, key: &str) -> DateTime<Utc> {
    if let Ok(dt) = doc.get_datetime(key) {
        return dt.to_chrono();
    }
    if let Ok(s) = doc.get_str(key) {
        if let Ok(parsed) = DateTime::parse_from_rfc3339(s) {
            return parsed.with_timezone(&Utc);
        }
    }
    if let Ok(ms) = doc.get_i64(key) {
        if let Some(parsed) = Utc.timestamp_millis_opt(ms).single() {
            return parsed;
        }
    }
    Utc::now()
}

pub fn doc_get_i32(doc: &Document, key: &str, default: i32) -> i32 {
    if let Ok(v) = doc.get_i32(key) {
        return v;
    }
    if let Ok(v) = doc.get_i64(key) {
        return v as i32;
    }
    if let Ok(v) = doc.get_f64(key) {
        return v as i32;
    }
    default
}

pub fn parse_active_rule(doc: &Document) -> Option<ActiveRuleDoc> {
    Some(ActiveRuleDoc {
        rule_id: doc.get_str("rule_id").ok()?.to_string(),
        name: doc.get_str("name").unwrap_or("").to_string(),
        rule_type: doc
            .get_str("rule_type")
            .unwrap_or("HEURISTIC_WEIGHT")
            .to_string(),
        pattern: doc.get_str("pattern").ok().map(|s| s.to_string()),
        weight: doc_get_i32(doc, "weight", 0),
        priority: doc_get_i32(doc, "priority", 10),
        enabled: doc.get_bool("enabled").unwrap_or(true),
        updated_at: doc_get_datetime(doc, "updated_at"),
    })
}

pub fn parse_prefix_quarantine(doc: &Document) -> Option<PrefixQuarantineDoc> {
    let distinct_numbers_seen = doc
        .get_array("distinct_numbers_seen")
        .map(|arr| {
            arr.iter()
                .filter_map(|b| b.as_str().map(|s| s.to_string()))
                .collect::<Vec<String>>()
        })
        .unwrap_or_default();

    Some(PrefixQuarantineDoc {
        prefix_7: doc.get_str("prefix_7").ok()?.to_string(),
        ddd: doc.get_str("ddd").unwrap_or("").to_string(),
        carrier_guess: doc.get_str("carrier_guess").ok().map(|s| s.to_string()),
        distinct_numbers_seen,
        hit_count: doc_get_i32(doc, "hit_count", 1),
        penalty_weight: doc_get_i32(doc, "penalty_weight", 60),
        reason: doc
            .get_str("reason")
            .unwrap_or("ROBOCALL_PREFIX_CLUSTER")
            .to_string(),
        first_seen_at: doc_get_datetime(doc, "first_seen_at"),
        last_seen_at: doc_get_datetime(doc, "last_seen_at"),
        expires_at: doc_get_datetime(doc, "expires_at"),
    })
}

pub fn parse_anatel_doc(doc: &Document) -> Option<AnatelNumberingPlanDoc> {
    let line_type = match doc.get_str("line_type").unwrap_or("UNKNOWN") {
        "MOBILE" => LineType::MOBILE,
        "FIXED_OR_VOIP" => LineType::FIXED_OR_VOIP,
        "TOLL_FREE_OR_SPECIAL" => LineType::TOLL_FREE_OR_SPECIAL,
        _ => LineType::UNKNOWN,
    };

    Some(AnatelNumberingPlanDoc {
        prefix_7: doc.get_str("prefix_7").ok()?.to_string(),
        ddd: doc.get_str("ddd").unwrap_or("").to_string(),
        carrier: doc.get_str("carrier").unwrap_or("UNKNOWN").to_string(),
        line_type,
        is_corporate_pabx_range: doc.get_bool("is_corporate_pabx_range").unwrap_or(false),
        updated_at: doc_get_datetime(doc, "updated_at"),
    })
}
