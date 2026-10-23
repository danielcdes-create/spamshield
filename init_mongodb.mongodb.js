// Seleciona e cria o banco de dados oficial
use("spam_shield_db");

// ============================================================================
// 1. COLEÇÃO: call_telemetry (Histórico e Auditoria de Chamadas Interceptadas)
// ============================================================================
db.createCollection("call_telemetry", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "timestamp", "device_id", "call_details", "decision_vector", "audit"],
      properties: {
        _id: { bsonType: "string", description: "UUID v4 gerado no Android" },
        timestamp: { bsonType: "date" },
        device_id: { bsonType: "string" },
        call_details: {
          bsonType: "object",
          required: ["raw_number", "normalized_number", "ddd", "prefix_7", "line_type", "stir_shaken_status"],
          properties: {
            raw_number: { bsonType: "string" },
            normalized_number: { bsonType: "string" },
            ddd: { bsonType: "string" },
            prefix_7: { bsonType: "string" },
            carrier_guess: { bsonType: ["string", "null"] },
            line_type: { enum: ["MOBILE", "FIXED_OR_VOIP", "TOLL_FREE_OR_SPECIAL", "UNKNOWN"] },
            stir_shaken_status: { enum: ["PASSED", "FAILED", "NOT_VERIFIED"] }
          }
        },
        decision_vector: {
          bsonType: "object",
          required: ["final_score", "threshold_applied", "outcome", "category", "processing_latency_ms", "triggered_rules"],
          properties: {
            final_score: { bsonType: "int" },
            threshold_applied: { bsonType: "int" },
            outcome: {
              enum: [
                "BLOCKED",
                "SHADOW_BLOCKED",
                "ALLOWED_CONTACT",
                "ALLOWED_EMERGENCY_REPEAT",
                "ALLOWED_OUTBOUND_GRACE",
                "ALLOWED_LOW_SCORE"
              ]
            },
            category: {
              enum: [
                "ROBOCALL_PREFIX_CLUSTER",
                "SPOOFING_STIR_FAILED",
                "KNOWN_BLACKLIST",
                "PING_CALL_ROBOCALL",
                "LEGITIMATE",
                "UNKNOWN_UNVERIFIED"
              ]
            },
            processing_latency_ms: { bsonType: "double" },
            shadow_mode_active: { bsonType: "bool" },
            call_duration_sec: { bsonType: ["int", "null"] },
            triggered_rules: {
              bsonType: "array",
              items: {
                bsonType: "object",
                required: ["rule_id", "weight"],
                properties: {
                  rule_id: { bsonType: "string" },
                  weight: { bsonType: "int" },
                  meta: { bsonType: ["string", "null"] }
                }
              }
            }
          }
        },
        audit: {
          bsonType: "object",
          required: ["user_feedback", "synced_at"],
          properties: {
            user_feedback: { enum: ["UNRATED", "CONFIRMED_SPAM", "FALSE_POSITIVE"] },
            synced_at: { bsonType: "date" }
          }
        }
      }
    }
  }
});

// Índices analíticos para call_telemetry
db.call_telemetry.createIndex({ "timestamp": -1 });
db.call_telemetry.createIndex({ "call_details.prefix_7": 1, "timestamp": -1 });
db.call_telemetry.createIndex({ "decision_vector.outcome": 1, "timestamp": -1 });
db.call_telemetry.createIndex({ "decision_vector.category": 1, "timestamp": -1 });
db.call_telemetry.createIndex({ "audit.user_feedback": 1 });

// ============================================================================
// 2. COLEÇÃO: active_rules (Pesos do Motor Heurístico e Regras Regex/Prefixos)
// ============================================================================
db.createCollection("active_rules");
db.active_rules.createIndex({ "rule_id": 1 }, { unique: true });
db.active_rules.createIndex({ "enabled": 1, "priority": 1 });

// Seed das regras fundamentais calibradas para o Brasil
db.active_rules.insertMany([
  {
    rule_id: "CONTACT_WHITELIST",
    name: "Número Salvo na Agenda",
    rule_type: "SHORT_CIRCUIT_ALLOW",
    pattern: null,
    weight: -1000,
    priority: 1,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "EMERGENCY_REPEATED_CALL",
    name: "Repetição de Emergência (2x em 3 min)",
    rule_type: "SHORT_CIRCUIT_ALLOW",
    pattern: null,
    weight: -500,
    priority: 2,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "OUTBOUND_GRACE_PERIOD",
    name: "Imunidade de Saída (Retorno de PBX em até 2h)",
    rule_type: "SHORT_CIRCUIT_ALLOW",
    pattern: null,
    weight: -500,
    priority: 3,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "STIR_SHAKEN_FAILED",
    name: "Falha de Autenticação STIR/SHAKEN (Spoofing)",
    rule_type: "HEURISTIC_WEIGHT",
    pattern: null,
    weight: 85,
    priority: 10,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "STIR_SHAKEN_UNVERIFIED",
    name: "Chamada Sem Assinatura STIR/SHAKEN",
    rule_type: "HEURISTIC_WEIGHT",
    pattern: null,
    weight: 20,
    priority: 11,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "NOT_IN_CONTACTS",
    name: "Número Desconhecido (Fora da Agenda)",
    rule_type: "HEURISTIC_WEIGHT",
    pattern: null,
    weight: 25,
    priority: 12,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "PREFIX_NEIGHBORHOOD_BURST",
    name: "Ataque de Vizinhança (Mesmo Prefixo 7 Dígitos nas últimas 24h)",
    rule_type: "HEURISTIC_WEIGHT",
    pattern: null,
    weight: 60,
    priority: 13,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "PING_CALL_PENALTY",
    name: "Histórico de Chamada Muda (< 4s)",
    rule_type: "HEURISTIC_WEIGHT",
    pattern: null,
    weight: 55,
    priority: 14,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "REGEX_MG_VOIP_TRUNKS",
    name: "Troncos VoIP/Massivos Detectados em MG (31 4101-XXXX)",
    rule_type: "REGEX_MATCH",
    pattern: "^(?:\\+55|0)?314101\\d{4}$",
    weight: 80,
    priority: 20,
    enabled: true,
    updated_at: new Date()
  },
  {
    rule_id: "REGEX_TELEMARKETING_NATIONAL",
    name: "Prefixos Nacionais de Telemarketing (0300, 0303, 4004)",
    rule_type: "REGEX_MATCH",
    pattern: "^(?:\\+55|0)?(?:300|303|4004)\\d+$",
    weight: 90,
    priority: 21,
    enabled: true,
    updated_at: new Date()
  }
]);

// ============================================================================
// 3. COLEÇÃO: prefix_quarantine (Clusters de Prefixos com Decaimento TTL)
// ============================================================================
db.createCollection("prefix_quarantine");
db.prefix_quarantine.createIndex({ "prefix_7": 1 }, { unique: true });
// Índice TTL: O MongoDB remove automaticamente a quarentena quando expires_at é atingido
db.prefix_quarantine.createIndex({ "expires_at": 1 }, { expireAfterSeconds: 0 });

// Seed com os prefixos reais observados no histórico do usuário (DDD 31)
const sevenDaysFromNow = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
db.prefix_quarantine.insertMany([
  {
    prefix_7: "3141011",
    ddd: "31",
    carrier_guess: "Claro Fixo / VoIP",
    distinct_numbers_seen: ["3141011230"],
    hit_count: 2,
    penalty_weight: 70,
    reason: "ROBOCALL_PREFIX_CLUSTER",
    first_seen_at: new Date(),
    last_seen_at: new Date(),
    expires_at: sevenDaysFromNow
  },
  {
    prefix_7: "3141013",
    ddd: "31",
    carrier_guess: "Claro Fixo / VoIP",
    distinct_numbers_seen: ["3141013701"],
    hit_count: 1,
    penalty_weight: 60,
    reason: "ROBOCALL_PREFIX_CLUSTER",
    first_seen_at: new Date(),
    last_seen_at: new Date(),
    expires_at: sevenDaysFromNow
  },
  {
    prefix_7: "3198621",
    ddd: "31",
    carrier_guess: "TIM",
    distinct_numbers_seen: ["3198621040"],
    hit_count: 1,
    penalty_weight: 50,
    reason: "SPOOFING_OR_ROBOCALL_MOBILE",
    first_seen_at: new Date(),
    last_seen_at: new Date(),
    expires_at: sevenDaysFromNow
  },
  {
    prefix_7: "3198321",
    ddd: "31",
    carrier_guess: "TIM",
    distinct_numbers_seen: ["31983211420"],
    hit_count: 1,
    penalty_weight: 50,
    reason: "SPOOFING_OR_ROBOCALL_MOBILE",
    first_seen_at: new Date(),
    last_seen_at: new Date(),
    expires_at: sevenDaysFromNow
  },
  {
    prefix_7: "3198586",
    ddd: "31",
    carrier_guess: "TIM",
    distinct_numbers_seen: ["31985862992"],
    hit_count: 1,
    penalty_weight: 50,
    reason: "SPOOFING_OR_ROBOCALL_MOBILE",
    first_seen_at: new Date(),
    last_seen_at: new Date(),
    expires_at: sevenDaysFromNow
  }
]);

// ============================================================================
// 4. COLEÇÃO: anatel_numbering_plan (Referência de Prefixos e Operadoras)
// ============================================================================
db.createCollection("anatel_numbering_plan");
db.anatel_numbering_plan.createIndex({ "prefix_7": 1 }, { unique: true });
db.anatel_numbering_plan.createIndex({ "ddd": 1, "line_type": 1 });

db.anatel_numbering_plan.insertMany([
  { prefix_7: "3141011", ddd: "31", carrier: "CLARO S.A.", line_type: "FIXED_OR_VOIP", is_corporate_pabx_range: true, updated_at: new Date() },
  { prefix_7: "3141013", ddd: "31", carrier: "CLARO S.A.", line_type: "FIXED_OR_VOIP", is_corporate_pabx_range: true, updated_at: new Date() },
  { prefix_7: "3198621", ddd: "31", carrier: "TIM S.A.", line_type: "MOBILE", is_corporate_pabx_range: false, updated_at: new Date() },
  { prefix_7: "3198321", ddd: "31", carrier: "TIM S.A.", line_type: "MOBILE", is_corporate_pabx_range: false, updated_at: new Date() },
  { prefix_7: "3198586", ddd: "31", carrier: "TIM S.A.", line_type: "MOBILE", is_corporate_pabx_range: false, updated_at: new Date() }
]);
