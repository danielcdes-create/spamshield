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

        /**
         * Two missed calls from one ordinary number do not quarantine.
         * A VoIP or robocall-like number still quarantines at the previous threshold of 2.
         * Three distinct numbers in the prefix window still quarantine.
         */
        fun shouldQuarantineUnansweredBurst(
            recentCallsSameNumber: Int,
            distinctNumbersInPrefix: Int,
            voipOrRobocall: Boolean
        ): Boolean {
            if (voipOrRobocall && (recentCallsSameNumber >= 2 || distinctNumbersInPrefix >= 2)) {
                return true
            }
            return distinctNumbersInPrefix >= 3
        }

        fun isVoipOrRobocallLike(
            lineType: String,
            carrierGuess: String?,
            triggeredRulesJson: String
        ): Boolean {
            if (lineType == "FIXED_OR_VOIP" || lineType == "TOLL_FREE_OR_SPECIAL") return true
            if (PhoneNormalizer.isVoipPrefixOrSubscriber(
                    subscriber = "",
                    prefix7 = "",
                    rawCarrier = carrierGuess,
                    isCorporatePabx = false
                )
            ) {
                return true
            }
            return triggeredRulesJson.contains("PING_CALL") ||
                triggeredRulesJson.contains("REGEX_") ||
                triggeredRulesJson.contains("ROBOCALL") ||
                triggeredRulesJson.contains("STIR_SHAKEN_FAILED")
        }
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
            val activeQ = dao.getActiveQuarantineForPrefix(normalized.prefix8, normalized.prefix7, nowMillis)
            if (activeQ == null) {
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
            "", "NOT_VERIFIED" -> {
                val w = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                score += w
                triggeredRules.add(TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", w, "VERIFICATION_STATUS_NOT_VERIFIED"))
            }
        }

        // 3.3 Quarentena de Prefixo Ativa (/8 milhar prioritário, fallback /7 legado e /5 fazenda de chipeira)
        var activeQuarantine: PrefixQuarantineEntity? = null
        var subBlockClusterTriggered = false
        if (normalized.prefix7.isNotEmpty()) {
            activeQuarantine = dao.getActiveQuarantineForPrefix(
                prefix8 = normalized.prefix8,
                prefix7 = normalized.prefix7,
                now = nowMillis
            )
            if (activeQuarantine != null) {
                score += activeQuarantine.penaltyWeight
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX_QUARANTINE_HIT",
                        weight = activeQuarantine.penaltyWeight,
                        meta = "${activeQuarantine.prefix7} (${activeQuarantine.reason})"
                    )
                )
            } else if (normalized.prefix6.length >= 6) {
                // Alerta leve (+15) se houver quarentenas vizinhas no mesmo sub-bloco /6,
                // sem bloquear falsos-positivos de outros milhares (25 + 20 + 15 = 60 < 70).
                val q6Count = dao.countActiveQuarantinesByPrefix6(normalized.prefix6, nowMillis)
                if (q6Count >= 1) {
                    val w6 = 15
                    score += w6
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

        // 3.4 Detecção Dinâmica de Vizinhança (Neighborhood Burst estrito no milhar /8 nas últimas 24h)
        var neighborhoodBurstTriggered = false
        val burstPrefix = if (normalized.prefix8.length == 8) normalized.prefix8 else normalized.prefix7
        if (burstPrefix.isNotEmpty()) {
            val distinctIn24h = dao.countDistinctNumbersInPrefix7Since(
                prefix7 = burstPrefix,
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
                        meta = "distinct_24h=$effectiveDistinctCount ($burstPrefix)"
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

        // Passo 4: Veredito, Modo Sombra, Quarentena Automática (/8) e Gravação
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

        val storagePrefix = if (normalized.prefix8.length == 8) normalized.prefix8 else normalized.prefix7

        if (isSpam && storagePrefix.length >= 6) {
            val updatedQuarantine = PrefixQuarantineEntity(
                prefix7 = storagePrefix,
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
            prefix7 = storagePrefix,
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

    fun extractQuarantinePrefix(normalizedNumber: String, fallbackPrefix7: String): String {
        val digits = normalizedNumber.filter { it.isDigit() }
        val nat = if (digits.startsWith("55") && digits.length in 12..13) digits.substring(2) else digits
        return if (nat.length >= 8) nat.take(8) else fallbackPrefix7
    }

    /**
     * Passo 5: Detector de "Ping Call" (Chamada Muda Pós-Atendimento)
     * Se uma chamada desconhecida com score < threshold (ALLOWED_LOW_SCORE) foi atendida
     * e durou entre 1 e 4 segundos, classifica como PING_CALL_ROBOCALL e quarentena o prefixo /8 (+65).
     */
    suspend fun evaluatePostCallPingDetector(
        normalizedNumber: String,
        callDurationSec: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (callDurationSec !in 1..4) return false
        val latest = dao.getLatestTelemetryForNumber(normalizedNumber) ?: return false
        if (latest.outcome != "ALLOWED_LOW_SCORE") return false

        val existingRules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
        }.getOrDefault(emptyList()).toMutableList()

        if (existingRules.none { it.rule_id == "PING_CALL_PENALTY" }) {
            existingRules.add(TriggeredRuleDto("PING_CALL_PENALTY", 55, "Duração ${callDurationSec}s"))
        }

        val qPrefix = extractQuarantinePrefix(latest.normalizedNumber, latest.prefix7)
        val updatedTelemetry = latest.copy(
            prefix7 = qPrefix.ifEmpty { latest.prefix7 },
            outcome = "BLOCKED",
            category = "PING_CALL_ROBOCALL",
            finalScore = latest.finalScore + 55,
            callDurationSec = callDurationSec,
            triggeredRulesJson = json.encodeToString(existingRules),
            syncStatus = "PENDING"
        )
        dao.insertTelemetry(updatedTelemetry)

        if (qPrefix.isNotEmpty()) {
            val existing = dao.getActiveQuarantineForPrefix(
                prefix8 = qPrefix,
                prefix7 = latest.prefix7,
                now = nowMillis
            )
            dao.upsertQuarantine(
                PrefixQuarantineEntity(
                    prefix7 = qPrefix,
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
     * Distingue com precisão:
     * - BLOCKED_TYPE (6) gerado pelo próprio bloqueio de borda do SpamShield (score inicial >= threshold):
     *   NÃO altera score nem adiciona SYSTEM_CALLLOG_BLOCKED (a chamada não tocou).
     * - REJECTED_TYPE (5) (chamada tocou e usuário rejeitou manualmente no discador) ou chamada com score inicial < threshold
     *   rejeitada/bloqueada no discador:
     *   Promove para BLOCKED (+45, regra SYSTEM_CALLLOG_BLOCKED — exibida como APRENDIDA na UI) e quarentena o prefixo /8 (+70).
     */
    suspend fun evaluatePostCallLogVerdict(
        normalizedNumber: String,
        callType: Int,
        callDurationSec: Int,
        isSystemBlocked: Boolean = false,
        callTimestampMs: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val latest = if (callTimestampMs != null) {
            dao.findRealTimeTelemetryNearTimestamp(
                normalizedNumber = normalizedNumber,
                minTs = callTimestampMs - 60_000L,
                maxTs = callTimestampMs + 60_000L
            ) ?: dao.getLatestTelemetryForNumber(normalizedNumber)
        } else {
            dao.getLatestTelemetryForNumber(normalizedNumber)
        } ?: return false

        val currentDuration = latest.callDurationSec

        // Se é contato da agenda ou chamada de saída protegida por grace, apenas atualiza duração
        if (latest.outcome == "ALLOWED_CONTACT" || latest.outcome == "ALLOWED_OUTBOUND_GRACE") {
            if (currentDuration == null && callDurationSec >= 0) {
                dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
            }
            return false
        }

        val existingRules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
        }.getOrDefault(emptyList())

        // Calcula o score limpo de chegada na borda (sem regras pós-chamada e sem o antigo falso-positivo de /6)
        val cleanArrivalRules = existingRules.filter {
            it.rule_id != "SYSTEM_CALLLOG_BLOCKED" &&
                it.rule_id != "PING_CALL_PENALTY" &&
                it.rule_id != "CONVERSATION_ANSWERED_BONUS" &&
                it.rule_id != "PREFIX6_CLUSTER_HIT"
        }
        val cleanArrivalScore = if (cleanArrivalRules.isNotEmpty()) {
            cleanArrivalRules.sumOf { it.weight }
        } else {
            latest.finalScore
        }

        // Uma chamada foi genuinamente bloqueada na borda se o score de chegada já era >= threshold.
        // Nesse caso, o Telecom grava BLOCKED_TYPE (6) porque nós a rejeitamos antes de tocar.
        val wasTrulyBlockedAtEdge = (latest.outcome == "BLOCKED" || latest.outcome == "SHADOW_BLOCKED") &&
            cleanArrivalScore >= latest.thresholdApplied

        if (wasTrulyBlockedAtEdge) {
            if (currentDuration == null && callDurationSec >= 0) {
                dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
            }
            return false
        }

        val wasBlockedOrRejectedInSystem = isSystemBlocked ||
            callType == android.provider.CallLog.Calls.BLOCKED_TYPE ||
            callType == android.provider.CallLog.Calls.REJECTED_TYPE

        // 1. Rejeição no Discador pelo usuário (REJECTED_TYPE) ou Bloqueio no Discador em chamada que tocou (cleanArrivalScore < threshold)
        if (wasBlockedOrRejectedInSystem && cleanArrivalScore < latest.thresholdApplied) {
            val updatedRules = cleanArrivalRules.toMutableList()
            val metaDesc = if (callType == android.provider.CallLog.Calls.BLOCKED_TYPE && !isSystemBlocked) {
                "Bloqueado no Sistema"
            } else {
                "Rejeitado no Discador"
            }
            updatedRules.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", 45, metaDesc))

            val qPrefix = extractQuarantinePrefix(latest.normalizedNumber, latest.prefix7)
            val updatedScore = maxOf(cleanArrivalScore + 45, 85)
            val updatedTelemetry = latest.copy(
                prefix7 = qPrefix.ifEmpty { latest.prefix7 },
                outcome = "BLOCKED",
                category = "ROBOCALL_PREFIX_CLUSTER",
                finalScore = updatedScore,
                callDurationSec = callDurationSec,
                triggeredRulesJson = json.encodeToString(updatedRules),
                syncStatus = "PENDING"
            )

            val rulesChanged = updatedTelemetry.triggeredRulesJson != latest.triggeredRulesJson ||
                updatedTelemetry.finalScore != latest.finalScore ||
                updatedTelemetry.outcome != latest.outcome ||
                updatedTelemetry.prefix7 != latest.prefix7

            if (rulesChanged || currentDuration != callDurationSec) {
                dao.insertTelemetry(updatedTelemetry)
            }

            if (qPrefix.isNotEmpty()) {
                val existingQ = dao.getActiveQuarantineForPrefix(
                    prefix8 = qPrefix,
                    prefix7 = latest.prefix7,
                    now = nowMillis
                )
                if (qPrefix.length == 8 && latest.prefix7.length == 7 && latest.prefix7 != qPrefix) {
                    dao.deleteQuarantineByPrefix(latest.prefix7)
                }
                dao.upsertQuarantine(
                    PrefixQuarantineEntity(
                        prefix7 = qPrefix,
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
            return rulesChanged
        }

        // 2. Ping Call (1..4s atendida)
        if (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec in 1..4) {
            return evaluatePostCallPingDetector(normalizedNumber, callDurationSec, nowMillis)
        }

        // 3. Chamada Perdida / Não Atendida (MISSED_TYPE ou duration == 0)
        val isUnanswered = callType == android.provider.CallLog.Calls.MISSED_TYPE ||
            (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec == 0)

        if (isUnanswered && cleanArrivalScore < latest.thresholdApplied) {
            val qPrefix = extractQuarantinePrefix(latest.normalizedNumber, latest.prefix7)
            dao.updateCallDurationAndCategory(latest.id, 0, latest.category)

            val recentCallsInPrefix = if (qPrefix.isNotEmpty()) {
                dao.countDistinctNumbersInPrefix7Since(qPrefix, nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS)
            } else {
                1
            }
            val recentCallsSameNum = dao.countRecentCallsFromNumber(latest.normalizedNumber, nowMillis - NEIGHBORHOOD_BURST_WINDOW_MS)

            val voipOrRobocall = isVoipOrRobocallLike(
                latest.lineType,
                latest.carrierGuess,
                latest.triggeredRulesJson
            )
            if (shouldQuarantineUnansweredBurst(recentCallsSameNum, recentCallsInPrefix, voipOrRobocall)) {
                if (qPrefix.isNotEmpty()) {
                    val existingQ = dao.getActiveQuarantineForPrefix(
                        prefix8 = qPrefix,
                        prefix7 = latest.prefix7,
                        now = nowMillis
                    )
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = qPrefix,
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
            prefix7 = if (normalized.prefix8.length == 8) normalized.prefix8 else normalized.prefix7,
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
