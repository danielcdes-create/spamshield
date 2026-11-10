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
            triggeredRulesJson: String,
            normalizedNumber: String = "",
            prefix7: String = ""
        ): Boolean {
            if (lineType == "TOLL_FREE_OR_SPECIAL") return true
            val subscriber = nationalSubscriber(normalizedNumber, prefix7)
            if (PhoneNormalizer.isVoipPrefixOrSubscriber(
                    subscriber = subscriber,
                    prefix7 = prefix7,
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

        /** National subscriber after the DDD. A geographic landline such as 32201234 is not a VoIP trunk. */
        fun nationalSubscriber(normalizedNumber: String, prefix7: String): String {
            val digits = normalizedNumber.filter { it.isDigit() }
            val national = if (digits.startsWith("55") && digits.length >= 12) digits.drop(2) else digits
            if (national.length > 2) return national.drop(2)
            if (prefix7.length > 2) return prefix7.drop(2)
            return national.ifEmpty { prefix7 }
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
                ?: normalized.prefix7.take(7).let { dao.getAnatelPrefix(it) }
        } else {
            null
        }
        val resolvedLineType = if (
            normalized.lineType == "MALFORMED_OR_SPOOFED" || normalized.isShortOrUtilityCode
        ) {
            normalized.lineType
        } else {
            anatelInfo?.lineType ?: normalized.lineType
        }
        val carrierGuess = if (
            anatelInfo != null &&
            normalized.lineType != "MALFORMED_OR_SPOOFED" &&
            !normalized.isShortOrUtilityCode
        ) {
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
            if (QuarantinePrefixIndex.match(normalized.storedPrefix7) == PrefixQuarantineMatch.NONE) {
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

        val enabledRules = dao.getEnabledRules().associateBy { it.ruleId }
        val compiledRules = enabledRules.values.mapNotNull { rule ->
            val pattern = rule.pattern
            if (rule.ruleType != "REGEX_MATCH" || pattern.isNullOrBlank()) {
                null
            } else {
                CompiledRegexRule(rule.ruleId, rule.weight, pattern, ScreeningPatternCache.pattern(pattern))
            }
        }
        val quarantineMatch = QuarantinePrefixIndex.match(normalized.storedPrefix7)
        val decision = HeuristicScorer.score(
            ScoreInput(
                rawNumber = rawNumber,
                normalizedNumber = normalized.normalizedNumber,
                ddd = normalized.ddd,
                prefix7 = normalized.storedPrefix7,
                subscriberNumber = normalized.subscriberNumber,
                carrierGuess = carrierGuess,
                lineType = resolvedLineType,
                stirShakenStatus = stirShakenStatus,
                inContacts = false,
                quarantineMatch = quarantineMatch,
                thresholdApplied = settings.blockThresholdScore,
                userLocalDdd = settings.userDdd,
                cspExtracted = normalized.cspExtracted,
                isShortOrUtilityCode = normalized.isShortOrUtilityCode,
                shadowMode = settings.shadowModeEnabled,
                unverifiedWeight = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20,
                extraRegexRules = compiledRules
            )
        )
        val triggeredRules = decision.triggeredRules.toMutableList()

        val burstPrefix = normalized.storedPrefix7.orEmpty()
        if (burstPrefix.length == 8) {
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
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX_NEIGHBORHOOD_BURST",
                        weight = burstWeight,
                        meta = "distinct_24h=$effectiveDistinctCount ($burstPrefix)"
                    )
                )
            }
        }

        if (quarantineMatch == PrefixQuarantineMatch.NONE && normalized.prefix6.length >= 6) {
            val neighborCount = QuarantinePrefixIndex.countWithPrefix(normalized.prefix6)
            if (neighborCount >= 1) {
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX6_NEIGHBOR_WARNING",
                        weight = 15,
                        meta = "Vizinho do sub-bloco ${normalized.prefix6}x em quarentena"
                    )
                )
            }
        }

        val prefix5 = normalized.prefix7.take(5)
        if (prefix5.length == 5) {
            val farmCount = QuarantinePrefixIndex.countWithPrefix(prefix5)
            if (PhoneNormalizer.isKnownChipeiraFarmPrefix5(prefix5) || farmCount >= 6) {
                triggeredRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX5_CHIPEIRA_FARM",
                        weight = 35,
                        meta = "Bloco ${prefix5}xx ($farmCount prefixos em quarentena)"
                    )
                )
            }
        }

        if (normalized.ddd.isNotEmpty() && normalized.ddd != "00" && normalized.ddd != settings.userDdd) {
            triggeredRules.add(
                TriggeredRuleDto(
                    rule_id = "EXTERNAL_DDD_UNKNOWN",
                    weight = 30,
                    meta = "DDD ${normalized.ddd} != ${settings.userDdd}"
                )
            )
        }

        val score = HeuristicScorer.equation(triggeredRules)
        val threshold = settings.blockThresholdScore
        val outcome = HeuristicScorer.resolveOutcome(score, threshold, null, settings.shadowModeEnabled)
        val category = when {
            triggeredRules.any { it.rule_id == "REGEX_TELEMARKETING_NATIONAL" || it.rule_id == "ANATEL_ATO_10413_0303" } ->
                "KNOWN_BLACKLIST"
            else -> HeuristicScorer.resolveCategory(stirShakenStatus, outcome, triggeredRules)
        }
        val storagePrefix = normalized.storedPrefix7.orEmpty()

        if (outcome == "BLOCKED" || outcome == "SHADOW_BLOCKED") {
            if (storagePrefix.length == 8) {
                val updatedQuarantine = PrefixQuarantineEntity(
                    prefix7 = storagePrefix,
                    ddd = normalized.ddd,
                    carrierGuess = carrierGuess,
                    distinctNumbersJson = "[\"${normalized.normalizedNumber}\"]",
                    hitCount = 1,
                    penaltyWeight = QuarantinePrefixIndex.EXACT_WEIGHT,
                    reason = category,
                    firstSeenAt = nowMillis,
                    lastSeenAt = nowMillis,
                    expiresAt = nowMillis + QUARANTINE_DEFAULT_TTL_MS
                )
                dao.upsertQuarantine(updatedQuarantine)
                QuarantinePrefixIndex.add(storagePrefix)
            }
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

        insertCoalesced(telemetryEntity)

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
        if (callDurationSec !in 1..11) return false
        val latest = dao.getLatestTelemetryForNumber(normalizedNumber) ?: return false
        if (latest.outcome != "ALLOWED_LOW_SCORE") return false

        val existingRules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
        }.getOrDefault(emptyList()).toMutableList()

        val band = HeuristicScorer.durationRule(callDurationSec)
        if (band != null && existingRules.none { it.rule_id == band.rule_id }) {
            existingRules.add(band)
        }

        val qPrefix = extractQuarantinePrefix(latest.normalizedNumber, latest.prefix7)
        val recomputed = HeuristicScorer.equation(existingRules)
        val answeredOutcome = HeuristicScorer.resolveOutcome(
            recomputed,
            latest.thresholdApplied,
            callDurationSec,
            false
        )
        val updatedTelemetry = latest.copy(
            prefix7 = qPrefix.ifEmpty { latest.prefix7 },
            outcome = answeredOutcome,
            category = when (band?.rule_id) {
                "PING_CALL_PENALTY" -> "PING_CALL_ROBOCALL"
                "SHORT_ROBOCALL_PENALTY" -> "ROBOCALL_PREFIX_CLUSTER"
                else -> latest.category
            },
            finalScore = recomputed,
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
            QuarantinePrefixIndex.add(qPrefix)
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

        if (wasTrulyBlockedAtEdge && callDurationSec <= 0) {
            if (currentDuration == null && callDurationSec >= 0) {
                dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
            }
            return false
        }

        if (wasTrulyBlockedAtEdge && callDurationSec > 0) {
            val rules = existingRules.toMutableList()
            HeuristicScorer.durationRule(callDurationSec)?.let { band ->
                if (rules.none { it.rule_id == band.rule_id }) rules.add(band)
            }
            val finalized = HeuristicScorer.finalize(
                rules,
                ScoreInput(
                    rawNumber = latest.rawNumber,
                    normalizedNumber = latest.normalizedNumber,
                    ddd = latest.ddd,
                    prefix7 = latest.prefix7.takeIf { it.length == 8 },
                    carrierGuess = latest.carrierGuess,
                    lineType = latest.lineType,
                    stirShakenStatus = latest.stirShakenStatus,
                    thresholdApplied = latest.thresholdApplied,
                    callDurationSec = callDurationSec
                )
            )
            dao.insertTelemetry(
                latest.copy(
                    callDurationSec = callDurationSec,
                    finalScore = finalized.finalScore,
                    outcome = finalized.outcome,
                    category = finalized.category,
                    triggeredRulesJson = UserFeedbackAudit.encode(finalized.triggeredRules),
                    syncStatus = "PENDING"
                )
            )
            return true
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
            if (callDurationSec > 0) {
                HeuristicScorer.durationRule(callDurationSec)?.let { band ->
                    if (updatedRules.none { it.rule_id == band.rule_id }) updatedRules.add(band)
                }
            }
            val finalized = HeuristicScorer.finalize(
                updatedRules,
                ScoreInput(
                    rawNumber = latest.rawNumber,
                    normalizedNumber = latest.normalizedNumber,
                    ddd = latest.ddd,
                    prefix7 = latest.prefix7.takeIf { it.length == 8 },
                    carrierGuess = latest.carrierGuess,
                    lineType = latest.lineType,
                    stirShakenStatus = latest.stirShakenStatus,
                    thresholdApplied = latest.thresholdApplied,
                    callDurationSec = callDurationSec
                )
            )

            val qPrefix = extractQuarantinePrefix(latest.normalizedNumber, latest.prefix7)
            val updatedTelemetry = latest.copy(
                prefix7 = qPrefix.ifEmpty { latest.prefix7 },
                outcome = finalized.outcome,
                category = finalized.category,
                finalScore = finalized.finalScore,
                callDurationSec = callDurationSec,
                triggeredRulesJson = json.encodeToString(finalized.triggeredRules),
                syncStatus = "PENDING"
            )

            val rulesChanged = updatedTelemetry.triggeredRulesJson != latest.triggeredRulesJson ||
                updatedTelemetry.finalScore != latest.finalScore ||
                updatedTelemetry.outcome != latest.outcome ||
                updatedTelemetry.prefix7 != latest.prefix7

            if (rulesChanged || currentDuration != callDurationSec) {
                dao.insertTelemetry(updatedTelemetry)
            }

            val shouldQuarantine = finalized.outcome == "BLOCKED" ||
                finalized.outcome == "FLAGGED_POST_CALL_QUARANTINED"
            if (shouldQuarantine && qPrefix.isNotEmpty()) {
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
                QuarantinePrefixIndex.add(qPrefix)
            }
            return rulesChanged
        }

        // 2. Chamada atendida: 1-5s ping, 6-11s robocall curto, >= 12s bônus
        if (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec in 1..11) {
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
                latest.triggeredRulesJson,
                latest.normalizedNumber,
                latest.prefix7.ifEmpty { qPrefix }
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
                    QuarantinePrefixIndex.add(qPrefix)
                    return true
                }
            }
        }

        // 4. Conversa atendida (>= 12s) recebe o bônus e o score é a soma das regras
        if (callDurationSec >= 12 && currentDuration == null) {
            val rules = existingRules.toMutableList()
            HeuristicScorer.durationRule(callDurationSec)?.let { band ->
                if (rules.none { it.rule_id == band.rule_id }) rules.add(band)
            }
            val recomputed = HeuristicScorer.equation(rules)
            val answeredOutcome = HeuristicScorer.resolveOutcome(
                recomputed,
                latest.thresholdApplied,
                callDurationSec,
                false
            )
            dao.insertTelemetry(
                latest.copy(
                    callDurationSec = callDurationSec,
                    finalScore = recomputed,
                    outcome = answeredOutcome,
                    category = HeuristicScorer.resolveCategory(latest.stirShakenStatus, answeredOutcome, rules),
                    triggeredRulesJson = json.encodeToString(rules),
                    syncStatus = "PENDING"
                )
            )
        }

        return false
    }

    private suspend fun insertCoalesced(entity: CallTelemetryEntity) {
        val prior = dao.getLatestTelemetryForNumber(entity.normalizedNumber)
        if (
            prior != null &&
            prior.id != entity.id &&
            kotlin.math.abs(prior.timestamp - entity.timestamp) <= TelemetryCoalescer.WINDOW_MS
        ) {
            dao.insertTelemetry(TelemetryCoalescer.merge(prior, entity))
        } else {
            dao.insertTelemetry(entity)
        }
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
