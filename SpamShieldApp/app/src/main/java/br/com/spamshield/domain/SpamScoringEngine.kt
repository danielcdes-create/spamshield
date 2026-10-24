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
                anatelInfo.isCorporatePabxRange,
                normalized.prefix7
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

        // 2.2 Repetição de Emergência (apenas MOBILE local sem quarentena ativa, sem cabeçalho CSP anômalo e sem falha STIR/SHAKEN)
        if (normalized.normalizedNumber.isNotEmpty() &&
            resolvedLineType == "MOBILE" &&
            stirShakenStatus != "FAILED" &&
            !PhoneNormalizer.hasAbnormalTrunkOrCspHeader(rawNumber, settings.userDdd)
        ) {
            val activeQ7 = dao.getActiveQuarantineForPrefix(normalized.prefix7, nowMillis)
            val activeQ6Count = dao.countActiveQuarantinesByPrefix6(normalized.prefix6, nowMillis)
            if (activeQ7 == null && activeQ6Count == 0) {
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
        }

        // 2.3 Imunidade de Saída (Outbound Grace Period nas últimas 2 horas)
        if (normalized.normalizedNumber.isNotEmpty()) {
            val dbGraceHits = dao.hasActiveOutboundGrace(
                normalizedNumber = normalized.normalizedNumber,
                prefix6 = normalized.prefix6,
                now = nowMillis
            )
            val callLogGrace = if (dbGraceHits > 0) {
                true
            } else {
                contactLookup.hasRecentOutgoingCall(
                    normalizedNumber = normalized.normalizedNumber,
                    prefix6 = normalized.prefix6,
                    sinceTimestampMs = nowMillis - OUTBOUND_GRACE_WINDOW_MS
                )
            }
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

        // Reinicia cronômetro após consultas IPC externas (Contacts/CallLog) para medir a árvore vetorial pura
        val engineStartNanos = safeElapsedNanos()

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
            "" , "NOT_VERIFIED" -> {
                val w = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                score += w
                triggeredRules.add(TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", w, "VERIFICATION_STATUS_NOT_VERIFIED"))
            }
        }

        // 3.3 Quarentena de Prefixo Ativa (/7 exato, /6 sub-bloco e /5 fazenda de chipeira)
        var activeQuarantine: PrefixQuarantineEntity? = null
        var subBlockClusterTriggered = false
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
            } else if (normalized.prefix6.length >= 6) {
                val q6Count = dao.countActiveQuarantinesByPrefix6(normalized.prefix6, nowMillis)
                val distinct6In24h = dao.countDistinctNumbersInPrefix6Since(
                    prefix6 = normalized.prefix6,
                    sinceTimestamp = nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS
                )
                if (q6Count >= 2 || distinct6In24h >= 2) {
                    val w6 = 45
                    score += w6
                    subBlockClusterTriggered = true
                    triggeredRules.add(
                        TriggeredRuleDto(
                            rule_id = "PREFIX6_CLUSTER_HIT",
                            weight = w6,
                            meta = "Sub-bloco ${normalized.prefix6}x (q=$q6Count, 24h=$distinct6In24h)"
                        )
                    )
                } else if (q6Count == 1) {
                    val w6 = 25
                    score += w6
                    subBlockClusterTriggered = true
                    triggeredRules.add(
                        TriggeredRuleDto(
                            rule_id = "PREFIX6_NEIGHBOR_WARNING",
                            weight = w6,
                            meta = "Vizinho do sub-bloco ${normalized.prefix6}x em quarentena"
                        )
                    )
                }
            }

            val prefix5 = normalized.prefix7.take(5)
            if (prefix5.length == 5) {
                val q5Count = dao.countActiveQuarantinesByPrefix5(prefix5, nowMillis)
                if (PhoneNormalizer.isKnownChipeiraFarmPrefix5(prefix5) || q5Count >= 6) {
                    val w5 = 35
                    score += w5
                    subBlockClusterTriggered = true
                    triggeredRules.add(
                        TriggeredRuleDto(
                            rule_id = "PREFIX5_CHIPEIRA_FARM",
                            weight = w5,
                            meta = "Bloco ${prefix5}xx ($q5Count prefixos em quarentena)"
                        )
                    )
                }
            }
        }

        // 3.4 Detecção Dinâmica de Vizinhança (Neighborhood Burst /7 nas últimas 24h)
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
            }
        }

        // 3.5 Regras Regex, Tronco Fixo/VoIP, DDD Externo e Discagem de Tronco com Zero (031/011)
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

        if (resolvedLineType == "FIXED_OR_VOIP" ||
            resolvedLineType == "TOLL_FREE_OR_SPECIAL" ||
            anatelInfo?.isCorporatePabxRange == true
        ) {
            val pabxWeight = 35
            score += pabxWeight
            triggeredRules.add(
                TriggeredRuleDto(
                    rule_id = "ANATEL_PABX_VOIP_RANGE",
                    weight = pabxWeight,
                    meta = carrierGuess
                )
            )
        }

        if (normalized.ddd.isNotEmpty() &&
            normalized.ddd != "00" &&
            normalized.ddd != settings.userDdd
        ) {
            val extDddWeight = 30
            score += extDddWeight
            triggeredRules.add(
                TriggeredRuleDto(
                    rule_id = "EXTERNAL_DDD_UNKNOWN",
                    weight = extDddWeight,
                    meta = "DDD ${normalized.ddd} != ${settings.userDdd}"
                )
            )
        }

        if (PhoneNormalizer.hasAbnormalTrunkOrCspHeader(rawNumber, settings.userDdd)) {
            val trunkZeroWeight = 25
            score += trunkZeroWeight
            triggeredRules.add(
                TriggeredRuleDto(
                    rule_id = "LEADING_ZERO_TRUNK_DIALING",
                    weight = trunkZeroWeight,
                    meta = rawNumber.trim().take(4)
                )
            )
        }

        // Passo 4: Veredito, Modo Sombra, Quarentena Automática e Gravação
        val threshold = settings.blockThresholdScore
        val isSpam = score >= threshold
        val outcome = when {
            isSpam && settings.shadowModeEnabled -> "SHADOW_BLOCKED"
            isSpam && !settings.shadowModeEnabled -> "BLOCKED"
            else -> "ALLOWED_LOW_SCORE"
        }

        val category = when {
            stirShakenStatus == "FAILED" -> "SPOOFING_STIR_FAILED"
            regexMatchedRuleId == "REGEX_TELEMARKETING_NATIONAL" || regexMatchedRuleId == "ANATEL_ATO_10413_0303" -> "KNOWN_BLACKLIST"
            activeQuarantine != null || neighborhoodBurstTriggered || subBlockClusterTriggered || regexMatchedRuleId != null -> "ROBOCALL_PREFIX_CLUSTER"
            isSpam -> "ROBOCALL_PREFIX_CLUSTER"
            else -> "UNKNOWN_UNVERIFIED"
        }

        if (isSpam && normalized.prefix7.length >= 6) {
            val updatedQuarantine = PrefixQuarantineEntity(
                prefix7 = normalized.prefix7,
                ddd = normalized.ddd,
                carrierGuess = carrierGuess,
                distinctNumbersJson = "[\"${normalized.normalizedNumber}\"]",
                hitCount = (activeQuarantine?.hitCount ?: 0) + 1,
                penaltyWeight = maxOf(activeQuarantine?.penaltyWeight ?: 65, 65),
                reason = category,
                firstSeenAt = activeQuarantine?.firstSeenAt ?: nowMillis,
                lastSeenAt = nowMillis,
                expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
            )
            dao.upsertQuarantine(updatedQuarantine)
        }

        val latencyMs = (safeElapsedNanos() - engineStartNanos).coerceAtLeast(100_000L) / 1_000_000.0

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

    /**
     * Passo 6: Aprendizado Ativo Pós-Chamada (Reconciliação com Veredito do Sistema/Discador e Chamadas Perdidas)
     * Quando o CallStateBroadcastReceiver ou a reconciliação do histórico detecta:
     * 1. Chamada REJEITADA pelo usuário/discador (REJECTED_TYPE) ou BLOQUEADA pelo sistema (BLOCKED_TYPE):
     *    - Promove a chamada para BLOCKED (+45, regra SYSTEM_CALLLOG_BLOCKED)
     *    - Quarentena imediatamente o prefix7 (+70) para que qualquer próxima chamada do prefixo seja barrada antes de tocar.
     * 2. PING CALL (atendida e durou 1..4s):
     *    - Classifica como PING_CALL_ROBOCALL e quarentena (+65).
     * 3. Chamada PERDIDA/NÃO ATENDIDA (MISSED_TYPE ou duration == 0) de número desconhecido:
     *    - Registra durationSec = 0.
     *    - Se o número ou prefixo já possui repetição não atendida nas últimas 24h sem retorno de saída:
     *      coloca o prefixo em quarentena preventiva (+65).
     */
    suspend fun evaluatePostCallLogVerdict(
        normalizedNumber: String,
        callType: Int,
        callDurationSec: Int,
        isSystemBlocked: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val latest = dao.getLatestTelemetryForNumber(normalizedNumber) ?: return false
        val currentDuration = latest.callDurationSec

        // Se é contato da agenda ou chamada de saída protegida por grace, apenas atualiza duração
        if (latest.outcome == "ALLOWED_CONTACT" || latest.outcome == "ALLOWED_OUTBOUND_GRACE") {
            if (currentDuration == null && callDurationSec >= 0) {
                dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
            }
            return false
        }

        val wasBlockedOrRejectedInSystem = isSystemBlocked ||
            callType == android.provider.CallLog.Calls.BLOCKED_TYPE ||
            callType == android.provider.CallLog.Calls.REJECTED_TYPE

        // 1. Rejeição no Discador ou Bloqueio no Sistema (ex: Google Dialer Spam Flag)
        if (wasBlockedOrRejectedInSystem) {
            val existingRules = runCatching {
                json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
            }.getOrDefault(emptyList()).toMutableList()

            if (existingRules.none { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }) {
                val metaDesc = if (callType == android.provider.CallLog.Calls.BLOCKED_TYPE) {
                    "Bloqueado no Sistema"
                } else {
                    "Rejeitado no Discador"
                }
                existingRules.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", 45, metaDesc))
            }

            val updatedScore = maxOf(latest.finalScore + 45, 85)
            val updatedTelemetry = latest.copy(
                outcome = "BLOCKED",
                category = "ROBOCALL_PREFIX_CLUSTER",
                finalScore = updatedScore,
                callDurationSec = callDurationSec,
                triggeredRulesJson = json.encodeToString(existingRules),
                syncStatus = "PENDING"
            )
            dao.insertTelemetry(updatedTelemetry)

            if (latest.prefix7.isNotEmpty()) {
                val existingQ = dao.getActiveQuarantineForPrefix(latest.prefix7, nowMillis)
                dao.upsertQuarantine(
                    PrefixQuarantineEntity(
                        prefix7 = latest.prefix7,
                        ddd = latest.ddd,
                        carrierGuess = latest.carrierGuess ?: "Outros",
                        distinctNumbersJson = "[\"${latest.normalizedNumber}\"]",
                        hitCount = (existingQ?.hitCount ?: 0) + 1,
                        penaltyWeight = 70,
                        reason = if (callType == android.provider.CallLog.Calls.BLOCKED_TYPE) "SYSTEM_CALLLOG_BLOCKED" else "DIALER_REJECTED",
                        firstSeenAt = existingQ?.firstSeenAt ?: nowMillis,
                        lastSeenAt = nowMillis,
                        expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
                    )
                )
            }
            return true
        }

        // 2. Ping Call (1..4s atendida)
        if (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec in 1..4) {
            return evaluatePostCallPingDetector(normalizedNumber, callDurationSec, nowMillis)
        }

        // 3. Chamada Perdida / Não Atendida (MISSED_TYPE ou duration == 0)
        val isUnanswered = callType == android.provider.CallLog.Calls.MISSED_TYPE ||
            (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec == 0)

        if (isUnanswered && latest.outcome == "ALLOWED_LOW_SCORE") {
            dao.updateCallDurationAndCategory(latest.id, 0, latest.category)

            val recentCallsInPrefix = if (latest.prefix7.isNotEmpty()) {
                dao.countDistinctNumbersInPrefix7Since(latest.prefix7, nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS)
            } else {
                1
            }
            val recentCallsSameNum = dao.countRecentCallsFromNumber(latest.normalizedNumber, nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS)

            if (recentCallsSameNum >= 2 || recentCallsInPrefix >= 2) {
                if (latest.prefix7.isNotEmpty()) {
                    val existingQ = dao.getActiveQuarantineForPrefix(latest.prefix7, nowMillis)
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = latest.prefix7,
                            ddd = latest.ddd,
                            carrierGuess = latest.carrierGuess ?: "Outros",
                            distinctNumbersJson = "[\"${latest.normalizedNumber}\"]",
                            hitCount = (existingQ?.hitCount ?: 0) + 1,
                            penaltyWeight = 65,
                            reason = "UNANSWERED_SPAM_BURST",
                            firstSeenAt = existingQ?.firstSeenAt ?: nowMillis,
                            lastSeenAt = nowMillis,
                            expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
                        )
                    )
                    return true
                }
            }
        }

        // 4. Conversa atendida normal (>= 10s)
        if (callDurationSec >= 10 && currentDuration == null) {
            dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
        }

        return false
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
