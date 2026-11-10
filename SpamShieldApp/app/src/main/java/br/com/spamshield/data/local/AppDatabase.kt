package br.com.spamshield.data.local

import android.database.Cursor
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.spamshield.domain.TelemetrySanitizer
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity

@Database(
    entities = [
        CallTelemetryEntity::class,
        CallDecisionEventEntity::class,
        ActiveRuleEntity::class,
        PrefixQuarantineEntity::class,
        OutboundGraceEntity::class,
        AnatelPrefixEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spamShieldDao(): SpamShieldDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep legacy rows intact. No fabricated arrival/response events.
                db.execSQL("""CREATE TABLE IF NOT EXISTS call_decision_events (
                    eventId TEXT NOT NULL PRIMARY KEY, callId TEXT NOT NULL, recordedAt INTEGER NOT NULL,
                    eventType TEXT NOT NULL, source TEXT NOT NULL, appVersion TEXT NOT NULL,
                    detailsJson TEXT NOT NULL, syncStatus TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_decision_events_callId_recordedAt ON call_decision_events(callId, recordedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_decision_events_syncStatus ON call_decision_events(syncStatus)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No column changes. Existing telemetry, rules, and quarantines stay.
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val rows = mutableListOf<CallTelemetryEntity>()
                db.query("SELECT * FROM call_telemetry").use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(readTelemetry(cursor))
                    }
                }
                if (rows.isEmpty()) return
                val sanitized = TelemetrySanitizer.sanitize(rows)
                db.execSQL("DELETE FROM call_telemetry")
                sanitized.forEach { row ->
                    db.execSQL(
                        """
                        INSERT INTO call_telemetry (
                            id, timestamp, deviceId, rawNumber, normalizedNumber, ddd, prefix7,
                            carrierGuess, lineType, stirShakenStatus, finalScore, thresholdApplied,
                            outcome, category, processingLatencyMs, shadowModeActive, callDurationSec,
                            triggeredRulesJson, userFeedback, syncStatus
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            row.id,
                            row.timestamp,
                            row.deviceId,
                            row.rawNumber,
                            row.normalizedNumber,
                            row.ddd,
                            row.prefix7,
                            row.carrierGuess,
                            row.lineType,
                            row.stirShakenStatus,
                            row.finalScore,
                            row.thresholdApplied,
                            row.outcome,
                            row.category,
                            row.processingLatencyMs,
                            if (row.shadowModeActive) 1 else 0,
                            row.callDurationSec,
                            row.triggeredRulesJson,
                            row.userFeedback,
                            row.syncStatus
                        )
                    )
                }
            }
        }

        private fun readTelemetry(cursor: Cursor): CallTelemetryEntity {
            fun string(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name))
            fun stringOrNull(name: String): String? {
                val index = cursor.getColumnIndexOrThrow(name)
                return if (cursor.isNull(index)) null else cursor.getString(index)
            }
            fun int(name: String): Int = cursor.getInt(cursor.getColumnIndexOrThrow(name))
            fun intOrNull(name: String): Int? {
                val index = cursor.getColumnIndexOrThrow(name)
                return if (cursor.isNull(index)) null else cursor.getInt(index)
            }
            return CallTelemetryEntity(
                id = string("id"),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                deviceId = string("deviceId"),
                rawNumber = string("rawNumber"),
                normalizedNumber = string("normalizedNumber"),
                ddd = string("ddd"),
                prefix7 = string("prefix7"),
                carrierGuess = stringOrNull("carrierGuess"),
                lineType = string("lineType"),
                stirShakenStatus = string("stirShakenStatus"),
                finalScore = int("finalScore"),
                thresholdApplied = int("thresholdApplied"),
                outcome = string("outcome"),
                category = string("category"),
                processingLatencyMs = cursor.getDouble(cursor.getColumnIndexOrThrow("processingLatencyMs")),
                shadowModeActive = int("shadowModeActive") != 0,
                callDurationSec = intOrNull("callDurationSec"),
                triggeredRulesJson = string("triggeredRulesJson"),
                userFeedback = string("userFeedback"),
                syncStatus = string("syncStatus")
            )
        }

        fun initialRulesSeed(now: Long = System.currentTimeMillis()): List<ActiveRuleEntity> = listOf(
            ActiveRuleEntity(
                ruleId = "CONTACT_WHITELIST",
                name = "Número Salvo na Agenda",
                ruleType = "SHORT_CIRCUIT_ALLOW",
                pattern = null,
                weight = -1000,
                priority = 1,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "EMERGENCY_REPEATED_CALL",
                name = "Repetição de Emergência (2x em 3 min)",
                ruleType = "SHORT_CIRCUIT_ALLOW",
                pattern = null,
                weight = -500,
                priority = 2,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "OUTBOUND_GRACE_PERIOD",
                name = "Imunidade de Saída (Retorno de PBX em até 2h)",
                ruleType = "SHORT_CIRCUIT_ALLOW",
                pattern = null,
                weight = -500,
                priority = 3,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "STIR_SHAKEN_FAILED",
                name = "Falha de Autenticação STIR/SHAKEN (Spoofing)",
                ruleType = "HEURISTIC_WEIGHT",
                pattern = null,
                weight = 85,
                priority = 10,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "STIR_SHAKEN_UNVERIFIED",
                name = "Chamada Sem Assinatura STIR/SHAKEN",
                ruleType = "HEURISTIC_WEIGHT",
                pattern = null,
                weight = 20,
                priority = 11,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "NOT_IN_CONTACTS",
                name = "Número Desconhecido (Fora da Agenda)",
                ruleType = "HEURISTIC_WEIGHT",
                pattern = null,
                weight = 25,
                priority = 12,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "PREFIX_NEIGHBORHOOD_BURST",
                name = "Ataque de Vizinhança (Mesmo Prefixo 7 Dígitos nas últimas 24h)",
                ruleType = "HEURISTIC_WEIGHT",
                pattern = null,
                weight = 60,
                priority = 13,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "PING_CALL_PENALTY",
                name = "Histórico de Chamada Muda (< 4s)",
                ruleType = "HEURISTIC_WEIGHT",
                pattern = null,
                weight = 55,
                priority = 14,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "REGEX_MG_VOIP_TRUNKS",
                name = "Troncos VoIP/Massivos Detectados em MG (31 4101-XXXX)",
                ruleType = "REGEX_MATCH",
                pattern = "^(?:\\+55|0)?314101\\d{4}$",
                weight = 80,
                priority = 20,
                enabled = true,
                updatedAt = now
            ),
            ActiveRuleEntity(
                ruleId = "REGEX_TELEMARKETING_NATIONAL",
                name = "Prefixos Nacionais de Telemarketing (0300, 0303, 4004)",
                ruleType = "REGEX_MATCH",
                pattern = "^(?:\\+55|0)?(?:300|303|4004)\\d+$",
                weight = 90,
                priority = 21,
                enabled = true,
                updatedAt = now
            )
        )

        fun initialQuarantineSeed(now: Long = System.currentTimeMillis()): List<PrefixQuarantineEntity> {
            val sevenDaysLater = now + 7L * 24 * 60 * 60 * 1000
            return listOf(
                PrefixQuarantineEntity(
                    prefix7 = "3141011",
                    ddd = "31",
                    carrierGuess = "VoIP",
                    distinctNumbersJson = "[\"3141011230\"]",
                    hitCount = 2,
                    penaltyWeight = 70,
                    reason = "ROBOCALL_PREFIX_CLUSTER",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = sevenDaysLater
                ),
                PrefixQuarantineEntity(
                    prefix7 = "3141013",
                    ddd = "31",
                    carrierGuess = "VoIP",
                    distinctNumbersJson = "[\"3141013701\"]",
                    hitCount = 1,
                    penaltyWeight = 60,
                    reason = "ROBOCALL_PREFIX_CLUSTER",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = sevenDaysLater
                ),
                PrefixQuarantineEntity(
                    prefix7 = "3198621",
                    ddd = "31",
                    carrierGuess = "TIM",
                    distinctNumbersJson = "[\"3198621040\"]",
                    hitCount = 1,
                    penaltyWeight = 50,
                    reason = "SPOOFING_OR_ROBOCALL_MOBILE",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = sevenDaysLater
                ),
                PrefixQuarantineEntity(
                    prefix7 = "3198321",
                    ddd = "31",
                    carrierGuess = "TIM",
                    distinctNumbersJson = "[\"31983211420\"]",
                    hitCount = 1,
                    penaltyWeight = 50,
                    reason = "SPOOFING_OR_ROBOCALL_MOBILE",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = sevenDaysLater
                ),
                PrefixQuarantineEntity(
                    prefix7 = "3198586",
                    ddd = "31",
                    carrierGuess = "TIM",
                    distinctNumbersJson = "[\"31985862992\"]",
                    hitCount = 1,
                    penaltyWeight = 50,
                    reason = "SPOOFING_OR_ROBOCALL_MOBILE",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = sevenDaysLater
                )
            )
        }

        fun initialAnatelSeed(now: Long = System.currentTimeMillis()): List<AnatelPrefixEntity> = listOf(
            AnatelPrefixEntity("3141011", "31", "VoIP", "FIXED_OR_VOIP", true, now),
            AnatelPrefixEntity("3141013", "31", "VoIP", "FIXED_OR_VOIP", true, now),
            AnatelPrefixEntity("3198621", "31", "TIM", "MOBILE", false, now),
            AnatelPrefixEntity("3198321", "31", "TIM", "MOBILE", false, now),
            AnatelPrefixEntity("3198586", "31", "TIM", "MOBILE", false, now)
        )
    }
}
