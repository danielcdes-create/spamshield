package br.com.spamshield.domain

import android.os.SystemClock
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ContactAndCallLogLookup {
    suspend fun isNumberInContacts(rawOrNormalizedNumber: String): Boolean
    suspend fun hasRecentOutgoingCall(normalizedNumber: String, prefix6: String, sinceTimestampMs: Long): Boolean
}

data class CallEvaluationResult(
    val telemetry: CallTelemetryEntity,
    val shouldDisallowAndReject: Boolean,
    val shouldSkipNotification: Boolean,
    val shouldSkipCallLog: Boolean,
    val shouldNotifyShadowBlock: Boolean
)

@Singleton
class SpamScoringEngine @Inject constructor(
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore,
    private val contactLookup: ContactAndCallLogLookup
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val EMERGENCY_REPEAT_WINDOW_MS = 180_000L // 3 minutes
        const val OUTBOUND_GRACE_WINDOW_MS = 2L * 60 * 60 * 1000L // 2 hours
        const val NEIGHBORHOOD_BURST_WINDOW_MS = 24L * 60 * 60 * 1000L // 24 hours
        const val QUARANTINE_DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
    }

    suspend fun evaluateIncomingCall(
        rawNumber: String,
        stirShakenStatus: String, // PASSED | FAILED | NOT_VERIFIED
        nowMillis: Long = System.currentTimeMillis()
    ): CallEvaluationResult {
        val startNanos = safeElapsedNanos()
        val settings = settingsDataStore.getSnapshot()

        // Passo 1: Normalização Estrita
        val normalized = PhoneNormalizer.normalize(rawNumber, settings.userDdd)
        val anatelInfo = if (normalized.prefix7.isNotEmpty()) {
            dao.getAnatelPrefix(normalized.prefix7)
        } else {
            null
        }
        val resolvedLineType = anatelInfo?.lineType ?: normalized.lineType
        val carrierGuess = if (anatelInfo != null) {
            PhoneNormalizer.normalizeCarrierName(
                anatelInfo.carrier,
                resolvedLineType,
                anatelInfo.isCorporatePabxRange
            )
        } else {
            normalized.carrierHint ?: "Outros"
        }

        // Passo 2: Curto-Circuito de Liberação (Prioridade Máxima)
        // 2.1 Agenda de Contatos
        if (normalized.normalizedNumber.isNotEmpty() &&
            contactLookup.isNumberInContacts(normalized.normalizedNumber)
        ) {
            return buildShortCircuitResult(
                normalized = normalized,
                carrierGuess = carrierGuess,
                lineType = resolvedLineType,
                stirShakenStatus = stirShakenStatus,
                finalScore = 0,
                threshold = settings.blockThresholdScore,
                shadowMode = settings.shadowModeEnabled,
                outcome = "ALLOWED_CONTACT",
                ruleId = "CONTACT_WHITELIST",
                ruleWeight = -1000,
                startNanos = startNanos,
                nowMillis = nowMillis
            )
        }

        // 2.2 Repetição de Emergência (mesmo normalizedNumber nos últimos 3 minutos)
        if (normalized.normalizedNumber.isNotEmpty()) {
            val recentSameNumberCount = dao.countRecentCallsFromNumber(
                normalizedNumber = normalized.normalizedNumber,
                sinceTimestamp = nowMillis - EMERGENCY_REPEAT_WINDOW_MS
            )
            if (recentSameNumberCount >= 1) {
                return buildShortCircuitResult(
                    normalized = normalized,
                    carrierGuess = carrierGuess,
                    lineType = resolvedLineType,
                    stirShakenStatus = stirShakenStatus,
                    finalScore = -100,
                    threshold = settings.blockThresholdScore,
                    shadowMode = settings.shadowModeEnabled,
                    outcome = "ALLOWED_EMERGENCY_REPEAT",
                    ruleId = "EMERGENCY_REPEATED_CALL",
                    ruleWeight = -500,
                    startNanos = startNanos,
                    nowMillis = nowMillis
                )
            }
        }

        // 2.3 Imunidade de Saída (Outbound Grace Period nas últimas 2 horas)
        if (normalized.normalizedNumber.isNotEmpty()) {
            val dbGraceHits = dao.hasActiveOutboundGrace(
                normalizedNumber = normalized.normalizedNumber,
                prefix6 = normalized.prefix6,
                now = nowMillis
            )
            val callLogGrace = contactLookup.hasRecentOutgoingCall(
                normalizedNumber = normalized.normalizedNumber,
                prefix6 = normalized.prefix6,
                sinceTimestampMs = nowMillis - OUTBOUND_GRACE_WINDOW_MS
            )
            if (dbGraceHits > 0 || callLogGrace) {
                return buildShortCircuitResult(
                    normalized = normalized,
                    carrierGuess = carrierGuess,
                    lineType = resolvedLineType,
                    stirShakenStatus = stirShakenStatus,
                    finalScore = -100,
                    threshold = settings.blockThresholdScore,
                    shadowMode = settings.shadowModeEnabled,
                    outcome = "ALLOWED_OUTBOUND_GRACE",
                    ruleId = "OUTBOUND_GRACE_PERIOD",
                    ruleWeight = -500,
                    startNanos = startNanos,
                    nowMillis = nowMillis
                )
            }
        }

        // Passo 3: Cálculo Vetorial de Score (Penalidades Cumulativas)
        val enabledRules = dao.getEnabledRules().associateBy { it.ruleId }
        var score = 0
        val triggeredRules = mutableListOf<TriggeredRuleDto>()

        // 3.1 Fora da Agenda (+25)
        val notInContactsWeight = enabledRules["NOT_IN_CONTACTS"]?.weight ?: 25
        score += notInContactsWeight
        triggeredRules.add(TriggeredRuleDto("NOT_IN_CONTACTS", notInContactsWeight, null))

        // 3.2 Validação STIR/SHAKEN
        when (stirShakenStatus) {
            "FAILED" -> {
                val w = enabledRules["STIR_SHAKEN_FAILED"]?.weight ?: 85
                score += w
                triggeredRules.add(TriggeredRuleDto("STIR_SHAKEN_FAILED", w, "VERIFICATION_STATUS_FAILED"))
            }
            "NOT_VERIFIED" -> {
                val w = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                score += w
                triggeredRules.add(TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", w, "VERIFICATION_STATUS_NOT_VERIFIED"))
            }
        }

        // 3.3 Quarentena de Prefixo Ativa
        var activeQuarantine: PrefixQuarantineEntity? = null
        if (normalized.prefix7.isNotEmpty()) {
            activeQuarantine = dao.getActiveQuarantineForPrefix(normalized.prefix7, nowMillis)
            if (activeQuarantine != null) {
                score += activeQuarantine.penaltyWeight
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX_QUARANTINE_HIT",
                        weight = activeQuarantine.penaltyWeight,
                        meta = "${normalized.prefix7} (${activeQuarantine.reason})"
                    )
                )
            }
        }

        // 3.4 Detecção Dinâmica de Vizinhança (Neighborhood Burst nas últimas 24h)
        var neighborhoodBurstTriggered = false
        if (normalized.prefix7.isNotEmpty()) {
            val distinctIn24h = dao.countDistinctNumbersInPrefix7Since(
                prefix7 = normalized.prefix7,
                sinceTimestamp = nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS
            )
            val prevCallForSameNumber = dao.countRecentCallsFromNumber(
                normalizedNumber = normalized.normalizedNumber,
                sinceTimestamp = nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS
            )
            // Total distinct numbers including the current incoming call
            val effectiveDistinctCount = if (prevCallForSameNumber == 0) distinctIn24h + 1 else distinctIn24h
            if (effectiveDistinctCount >= 2) {
                val burstWeight = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                score += burstWeight
                neighborhoodBurstTriggered = true
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX_NEIGHBORHOOD_BURST",
                        weight = burstWeight,
                        meta = "distinct_24h=$effectiveDistinctCount"
                    )
                )
                // Auto-insert/update prefix_quarantine with 7-day TTL
                val updatedQuarantine = PrefixQuarantineEntity(
                    prefix7 = normalized.prefix7,
                    ddd = normalized.ddd,
                    carrierGuess = carrierGuess ?: activeQuarantine?.carrierGuess ?: "Desconhecida",
                    distinctNumbersJson = "[\"${normalized.nationalDigits}\"]",
                    hitCount = (activeQuarantine?.hitCount ?: 1) + 1,
                    penaltyWeight = maxOf(activeQuarantine?.penaltyWeight ?: 60, 60),
                    reason = "ROBOCALL_PREFIX_CLUSTER",
                    firstSeenAt = activeQuarantine?.firstSeenAt ?: nowMillis,
                    lastSeenAt = nowMillis,
                    expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
                )
                dao.upsertQuarantine(updatedQuarantine)
            }
        }

        // 3.5 Regras Regex e Plano Anatel
        var regexMatchedRuleId: String? = null
        val regexRules = enabledRules.values.filter { it.ruleType == "REGEX_MATCH" && !it.pattern.isNullOrBlank() }
        for (rule in regexRules) {
            val regex = runCatching { Regex(rule.pattern!!) }.getOrNull() ?: continue
            if (regex.matches(normalized.rawNumber) ||
                regex.matches(normalized.normalizedNumber) ||
                regex.matches(normalized.nationalDigits)
            ) {
                score += rule.weight
                regexMatchedRuleId = rule.ruleId
                triggeredRules.add(TriggeredRuleDto(rule.ruleId, rule.weight, rule.pattern))
            }
        }

        if (anatelInfo?.isCorporatePabxRange == true) {
            val pabxWeight = 35
            score += pabxWeight
            triggeredRules.add(
                TriggeredRuleDto(
                    rule_id = "ANATEL_PABX_VOIP_RANGE",
                    weight = pabxWeight,
                    meta = anatelInfo.carrier
                )
            )
        }

        // Passo 4: Veredito, Modo Sombra e Gravação
        val threshold = settings.blockThresholdScore
        val isSpam = score >= threshold
        val outcome = when {
            isSpam && settings.shadowModeEnabled -> "SHADOW_BLOCKED"
            isSpam && !settings.shadowModeEnabled -> "BLOCKED"
            else -> "ALLOWED_LOW_SCORE"
        }

        val category = when {
            stirShakenStatus == "FAILED" -> "SPOOFING_STIR_FAILED"
            regexMatchedRuleId == "REGEX_TELEMARKETING_NATIONAL" -> "KNOWN_BLACKLIST"
            activeQuarantine != null || neighborhoodBurstTriggered || regexMatchedRuleId != null -> "ROBOCALL_PREFIX_CLUSTER"
            isSpam -> "ROBOCALL_PREFIX_CLUSTER"
            else -> "UNKNOWN_UNVERIFIED"
        }

        val latencyMs = (safeElapsedNanos() - startNanos).coerceAtLeast(100_000L) / 1_000_000.0

        val telemetryEntity = CallTelemetryEntity(
            id = UUID.randomUUID().toString(),
            timestamp = nowMillis,
            deviceId = "android-edge-br",
            rawNumber = normalized.rawNumber,
            normalizedNumber = normalized.normalizedNumber,
            ddd = normalized.ddd,
            prefix7 = normalized.prefix7,
            carrierGuess = carrierGuess,
            lineType = resolvedLineType,
            stirShakenStatus = stirShakenStatus,
            finalScore = score,
            thresholdApplied = threshold,
            outcome = outcome,
            category = category,
            processingLatencyMs = latencyMs,
            shadowModeActive = settings.shadowModeEnabled,
            callDurationSec = null,
            triggeredRulesJson = json.encodeToString(triggeredRules),
            userFeedback = "UNRATED",
            syncStatus = "PENDING"
        )

        dao.insertTelemetry(telemetryEntity)

        val shouldBlock = outcome == "BLOCKED"
        return CallEvaluationResult(
            telemetry = telemetryEntity,
            shouldDisallowAndReject = shouldBlock,
            shouldSkipNotification = shouldBlock,
            shouldSkipCallLog = shouldBlock && settings.hideBlockedFromSystemCallLog,
            shouldNotifyShadowBlock = outcome == "SHADOW_BLOCKED"
        )
    }

    /**
     * Passo 5: Detector de "Ping Call" (Chamada Muda Pós-Atendimento)
     * Se uma chamada desconhecida com score < threshold (ALLOWED_LOW_SCORE) foi atendida
     * e durou entre 1 e 4 segundos, classifica como PING_CALL_ROBOCALL e quarentena o prefix7 (+65).
     */
    suspend fun evaluatePostCallPingDetector(
        normalizedNumber: String,
        callDurationSec: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (callDurationSec !in 1..4) return false
        val latest = dao.getLatestTelemetryForNumber(normalizedNumber) ?: return false
        if (latest.outcome != "ALLOWED_LOW_SCORE") return false

        dao.updateCallDurationAndCategory(
            id = latest.id,
            durationSec = callDurationSec,
            category = "PING_CALL_ROBOCALL"
        )

        if (latest.prefix7.isNotEmpty()) {
            val existing = dao.getActiveQuarantineForPrefix(latest.prefix7, nowMillis)
            dao.upsertQuarantine(
                PrefixQuarantineEntity(
                    prefix7 = latest.prefix7,
                    ddd = latest.ddd,
                    carrierGuess = latest.carrierGuess ?: "Desconhecida",
                    distinctNumbersJson = "[\"${latest.normalizedNumber}\"]",
                    hitCount = (existing?.hitCount ?: 0) + 1,
                    penaltyWeight = 65,
                    reason = "PING_CALL_ROBOCALL",
                    firstSeenAt = existing?.firstSeenAt ?: nowMillis,
                    lastSeenAt = nowMillis,
                    expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
                )
            )
        }
        return true
    }

    private suspend fun buildShortCircuitResult(
        normalized: NormalizedPhone,
        carrierGuess: String?,
        lineType: String,
        stirShakenStatus: String,
        finalScore: Int,
        threshold: Int,
        shadowMode: Boolean,
        outcome: String,
        ruleId: String,
        ruleWeight: Int,
        startNanos: Long,
        nowMillis: Long
    ): CallEvaluationResult {
        val rules = listOf(TriggeredRuleDto(ruleId, ruleWeight, null))
        val latencyMs = (safeElapsedNanos() - startNanos).coerceAtLeast(50_000L) / 1_000_000.0
        val entity = CallTelemetryEntity(
            id = UUID.randomUUID().toString(),
            timestamp = nowMillis,
            deviceId = "android-edge-br",
            rawNumber = normalized.rawNumber,
            normalizedNumber = normalized.normalizedNumber,
            ddd = normalized.ddd,
            prefix7 = normalized.prefix7,
            carrierGuess = carrierGuess,
            lineType = lineType,
            stirShakenStatus = stirShakenStatus,
            finalScore = finalScore,
            thresholdApplied = threshold,
            outcome = outcome,
            category = "LEGITIMATE",
            processingLatencyMs = latencyMs,
            shadowModeActive = shadowMode,
            callDurationSec = null,
            triggeredRulesJson = json.encodeToString(rules),
            userFeedback = "UNRATED",
            syncStatus = "PENDING"
        )
        dao.insertTelemetry(entity)
        return CallEvaluationResult(
            telemetry = entity,
            shouldDisallowAndReject = false,
            shouldSkipNotification = false,
            shouldSkipCallLog = false,
            shouldNotifyShadowBlock = false
        )
    }

    private fun safeElapsedNanos(): Long {
        return try {
            val rt = SystemClock.elapsedRealtimeNanos()
            if (rt > 0L) rt else System.nanoTime()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }
}
