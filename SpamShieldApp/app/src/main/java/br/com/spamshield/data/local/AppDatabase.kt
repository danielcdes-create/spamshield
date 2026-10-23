package br.com.spamshield.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity

@Database(
    entities = [
        CallTelemetryEntity::class,
        ActiveRuleEntity::class,
        PrefixQuarantineEntity::class,
        OutboundGraceEntity::class,
        AnatelPrefixEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spamShieldDao(): SpamShieldDao

    companion object {
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
