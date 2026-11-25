package br.com.spamshield.domain

import android.os.SystemClock
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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

internal data class PreparedScreening(
    val result: CallEvaluationResult,
    val window: ExpectedWindow? = null,
    val quarantineMatch: PrefixQuarantineMatch = PrefixQuarantineMatch.NONE,
    val decisionDetails: () -> DecisionDetails
)

internal class PendingScreeningAudit(val normalizedNumber: String, val timestamp: Long) {
    val persisted = CompletableDeferred<Unit>()
    val retryCallLog = AtomicBoolean(false)
}

@Singleton
class SpamScoringEngine @Inject constructor(
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore,
    private val contactLookup: ContactAndCallLogLookup
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingScreeningAudits = ConcurrentHashMap.newKeySet<PendingScreeningAudit>()

    companion object {
        const val EMERGENCY_REPEAT_WINDOW_MS = 180_000L // 3 minutes
        const val OUTBOUND_GRACE_WINDOW_MS = 2L * 60 * 60 * 1000L // 2 hours
        const val NEIGHBORHOOD_BURST_WINDOW_MS = 24L * 60 * 60 * 1000L // 24 hours
        const val QUARANTINE_DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
        private const val PENDING_CALL_LOG_WINDOW_MS = 2_000L
        private const val SCREENING_AUDIT_WAIT_MS = 1_500L

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
        stirShakenStatus: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CallEvaluationResult = prepareIncomingCall(rawNumber, stirShakenStatus, nowMillis).result

    internal suspend fun prepareIncomingCall(
        rawNumber: String,
        stirShakenStatus: String,
        nowMillis: Long,
        timing: ScreeningTiming? = null,
        onWindowSnapshot: (ExpectedWindow?) -> Unit = {}
    ): PreparedScreening {
        val startNanos = safeElapsedNanos()
        val settings = settingsDataStore.getSnapshot()
        onWindowSnapshot(settings.expectedWindow)
        if (!QuarantinePrefixIndex.isReady) {
            QuarantinePrefixIndex.refreshActive(nowMillis) { dao.getAllQuarantines() }
        }

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
                nowMillis = nowMillis,
                settings = settings,
                timing = timing
            )
        }

        // 2.2 Repetição de Emergência (apenas MOBILE local sem quarentena ativa, sem cabeçalho CSP anômalo e sem falha STIR/SHAKEN)
        if (normalized.normalizedNumber.isNotEmpty() &&
            resolvedLineType == "MOBILE" &&
            stirShakenStatus != "FAILED" &&
            !PhoneNormalizer.hasAbnormalTrunkOrCspHeader(rawNumber, settings.userDdd)
        ) {
            if (QuarantinePrefixIndex.match(normalized.storedPrefix7, nowMillis) == PrefixQuarantineMatch.NONE) {
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
                        finalScore = 0,
                        threshold = settings.blockThresholdScore,
                        shadowMode = settings.shadowModeEnabled,
                        outcome = "ALLOWED_EMERGENCY_REPEAT",
                        ruleId = "EMERGENCY_REPEATED_CALL",
                        ruleWeight = -500,
                        startNanos = startNanos,
                        nowMillis = nowMillis,
                        settings = settings,
                        timing = timing
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
                    finalScore = 0,
                    threshold = settings.blockThresholdScore,
                    shadowMode = settings.shadowModeEnabled,
                    outcome = "ALLOWED_OUTBOUND_GRACE",
                    ruleId = "OUTBOUND_GRACE_PERIOD",
                    ruleWeight = -500,
                    startNanos = startNanos,
                    nowMillis = nowMillis,
                    settings = settings,
                    timing = timing
                )
            }
        }

        // Reinicia cronômetro após consultas IPC externas (Contacts/CallLog) para medir a árvore vetorial pura
        val engineStartNanos = safeElapsedNanos()

        val allRules = dao.observeAllRules().first()
        val enabledRules = allRules.filter { it.enabled }.associateBy { it.ruleId }
        val ruleWeights = allRules.associate { it.ruleId to it.weight }
        val disabledRuleIds = allRules.filterNot { it.enabled }.map { it.ruleId }.toSet()
        val compiledRules = enabledRules.values.mapNotNull { rule ->
            val pattern = rule.pattern
            val regex = if (rule.ruleType == "REGEX_MATCH" && !pattern.isNullOrBlank()) {
                ScreeningPatternCache.patternOrNull(pattern)
            } else {
                null
            }
            if (regex == null || pattern == null) {
                null
            } else {
                CompiledRegexRule(rule.ruleId, rule.weight, pattern, regex)
            }
        }
        val quarantineMatch = QuarantinePrefixIndex.match(normalized.storedPrefix7, nowMillis)
        val scoreInput = ScoreInput(
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
                ruleWeights = ruleWeights,
                disabledRuleIds = disabledRuleIds,
                extraRegexRules = compiledRules
            )
        val contextualRules = mutableListOf<TriggeredRuleDto>()
        var prefix6Count: Int? = null
        var prefix5Count: Int? = null

        var distinctNumbers24h: Int? = null
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
            distinctNumbers24h = effectiveDistinctCount
            if (effectiveDistinctCount >= 2) {
                val burstWeight = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                contextualRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX_NEIGHBORHOOD_BURST",
                        weight = burstWeight,
                        meta = "distinct_24h=$effectiveDistinctCount ($burstPrefix)"
                    )
                )
            }
        }

        if (quarantineMatch == PrefixQuarantineMatch.NONE && normalized.prefix6.length >= 6) {
            val neighborCount = QuarantinePrefixIndex.countWithPrefix(normalized.prefix6, nowMillis)
            prefix6Count = neighborCount
            if (neighborCount >= 1) {
                contextualRules.add(
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
            val farmCount = QuarantinePrefixIndex.countWithPrefix(prefix5, nowMillis)
            prefix5Count = farmCount
            if (PhoneNormalizer.isKnownChipeiraFarmPrefix5(prefix5) || farmCount >= 6) {
                contextualRules.add(
                    TriggeredRuleDto(
                        rule_id = "PREFIX5_CHIPEIRA_FARM",
                        weight = 35,
                        meta = "Bloco ${prefix5}xx ($farmCount prefixos em quarentena)"
                    )
                )
            }
        }

        if (normalized.ddd.isNotEmpty() && normalized.ddd != "00" && normalized.ddd != settings.userDdd) {
            contextualRules.add(
                TriggeredRuleDto(
                    rule_id = "EXTERNAL_DDD_UNKNOWN",
                    weight = 30,
                    meta = "DDD ${normalized.ddd} != ${settings.userDdd}"
                )
            )
        }

        timing?.mark("CONTEXT_READY")
        val decision = HeuristicScorer.score(scoreInput)
        val triggeredRules = HeuristicScorer.configuredRules(decision.triggeredRules + contextualRules, scoreInput)
        val score = HeuristicScorer.equation(triggeredRules)
        val threshold = settings.blockThresholdScore
        val outcome = HeuristicScorer.resolveOutcome(score, threshold, null, settings.shadowModeEnabled)
        val category = when {
            triggeredRules.any { it.rule_id == "REGEX_TELEMARKETING_NATIONAL" || it.rule_id == "ANATEL_ATO_10413_0303" } ->
                "KNOWN_BLACKLIST"
            else -> HeuristicScorer.resolveCategory(stirShakenStatus, outcome, triggeredRules)
        }
        val storagePrefix = normalized.storedPrefix7.orEmpty()

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

        val cacheReady = QuarantinePrefixIndex.isReady
        val cacheSize = QuarantinePrefixIndex.activeSize(nowMillis)
        val shouldBlock = outcome == "BLOCKED"
        val result = CallEvaluationResult(
            telemetry = telemetryEntity,
            shouldDisallowAndReject = shouldBlock,
            shouldSkipNotification = shouldBlock,
            shouldSkipCallLog = shouldBlock && settings.hideBlockedFromSystemCallLog,
            shouldNotifyShadowBlock = outcome == "SHADOW_BLOCKED"
        )
        timing?.mark("RULES_READY")
        return PreparedScreening(result, settings.expectedWindow, quarantineMatch) {
            val context = ArrivalContext(carrierGuess, resolvedLineType, settings.userDdd,
                settings.hideBlockedFromSystemCallLog, contextualRules.map { it.copy(meta = null) },
                prefix6Count, prefix5Count)
            val configuration = DecisionTrace.configuration(allRules + RuleLaboratory.catalog(allRules).filter { candidate ->
                allRules.none { it.ruleId == candidate.ruleId }
            })
            DecisionTrace.snapshot(telemetryEntity).copy(
                evaluatedRules = configuration,
                ruleSetFingerprint = DecisionTrace.configurationFingerprint(configuration, threshold,
                    settings.shadowModeEnabled, settings.userDdd, settings.hideBlockedFromSystemCallLog),
                arrivalContext = context,
                quarantineMatch = quarantineMatch.name,
                quarantineCacheReady = cacheReady,
                quarantineCacheSize = cacheSize,
                distinctNumbers24h = distinctNumbers24h
            )
        }
    }

    internal suspend fun recordIncomingCall(
        prepared: PreparedScreening,
        submittedAt: Long,
        response: DecisionDetails? = null,
        responseFailure: String? = null
    ): CallEvaluationResult {
        val result = prepared.result
        val callId = result.telemetry.id
        val source = "CALL_SCREENING_SERVICE"
        val recordedAt = if (response?.callAt != null) System.currentTimeMillis() else submittedAt
        val events = buildList {
            add(DecisionTrace.buildEvent(callId, "SCREENING_DECISION", source, prepared.decisionDetails(), recordedAt, key = "arrival"))
            if (response != null) {
                val eventType = if (responseFailure == null) "SCREENING_RESPONSE_SUBMITTED" else "SCREENING_RESPONSE_FAILED"
                add(DecisionTrace.buildEvent(callId, eventType, source, response.copy(reason = responseFailure ?: response.reason), recordedAt, key = "submission"))
            }
        }
        val row = dao.persistScreeningAudit(result.telemetry, events)
        if (responseFailure == null && response?.disallowCall == true && result.shouldDisallowAndReject) {
            learnFromCall(row, row.prefix7, row.category, QuarantinePrefixIndex.EXACT_WEIGHT, submittedAt, source)
        }
        return result.copy(telemetry = result.telemetry.copy(id = row.id))
    }

    internal fun beginScreeningAudit(normalizedNumber: String, timestamp: Long): PendingScreeningAudit =
        PendingScreeningAudit(normalizedNumber, timestamp).also { pendingScreeningAudits.add(it) }

    internal fun endScreeningAudit(pending: PendingScreeningAudit): Boolean {
        pendingScreeningAudits.remove(pending)
        pending.persisted.complete(Unit)
        return pending.retryCallLog.get()
    }

    fun extractQuarantinePrefix(
        normalizedNumber: String,
        fallbackPrefix7: String,
        defaultDdd: String = "31"
    ): String {
        val cleanFallback = fallbackPrefix7.takeIf { it.length in 7..8 && it.all(Char::isDigit) }.orEmpty()
        if (normalizedNumber.isBlank()) return cleanFallback
        val normalized = PhoneNormalizer.normalize(normalizedNumber, defaultDdd)
        if (normalized.isShortOrUtilityCode || normalized.lineType == "TOLL_FREE_OR_SPECIAL") {
            return ""
        }
        return normalized.storedPrefix7 ?: cleanFallback
    }

    /**
     * Passo 5: Detector de "Ping Call" (Chamada Muda Pós-Atendimento)
     * Se uma chamada desconhecida com score < threshold (ALLOWED_LOW_SCORE) foi atendida
     * e durou entre 1 e 4 segundos, classifica como PING_CALL_ROBOCALL e quarentena o prefixo /8 (+65).
     */
    suspend fun evaluatePostCallPingDetector(
        normalizedNumber: String,
        callDurationSec: Int,
        nowMillis: Long = System.currentTimeMillis(),
        callId: String? = null,
        configuration: Collection<ActiveRuleEntity>? = null
    ): Boolean {
        if (callDurationSec !in 1..11) return false
        val latest = (if (callId != null) dao.getTelemetryById(callId) else dao.getLatestTelemetryForNumber(normalizedNumber)) ?: return false
        if (latest.outcome != "ALLOWED_LOW_SCORE") return false

        val existingRules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
        }.getOrDefault(emptyList()).toMutableList()

        val band = HeuristicScorer.durationRule(callDurationSec)
        if (band != null && existingRules.none { it.rule_id == band.rule_id }) {
            existingRules.add(band)
        }

        val allRules = configuration ?: dao.observeAllRules().first()
        val finalized = HeuristicScorer.finalize(existingRules, ScoreInput(
            rawNumber = latest.rawNumber, stirShakenStatus = latest.stirShakenStatus,
            thresholdApplied = latest.thresholdApplied, callDurationSec = callDurationSec,
            ruleWeights = allRules.associate { it.ruleId to it.weight },
            disabledRuleIds = allRules.filterNot { it.enabled }.map { it.ruleId }.toSet()
        ))
        val qPrefix = extractQuarantinePrefix(
            latest.normalizedNumber,
            latest.prefix7,
            latest.ddd.takeIf { it.length == 2 } ?: settingsDataStore.getSnapshot().userDdd
        )
        val recomputed = finalized.finalScore
        val updatedTelemetry = latest.copy(
            prefix7 = qPrefix.ifEmpty { latest.prefix7 },
            outcome = finalized.outcome,
            category = finalized.category,
            finalScore = recomputed,
            callDurationSec = callDurationSec,
            triggeredRulesJson = json.encodeToString(finalized.triggeredRules),
            syncStatus = "PENDING"
        )
        dao.insertTelemetry(updatedTelemetry)

        if (recomputed >= latest.thresholdApplied) {
            learnFromCall(latest, qPrefix, "PING_CALL_ROBOCALL", 65, nowMillis)
        }
        return true
    }

    /**
     * Android records automatic blocks and manual dialer rejections as distinct observations.
     * Only a manual rejection can contribute the rejection scoring rule.
     */
    suspend fun evaluatePostCallLogVerdict(
        normalizedNumber: String,
        callType: Int,
        callDurationSec: Int,
        @Suppress("UNUSED_PARAMETER") isSystemBlocked: Boolean = false,
        callTimestampMs: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
        systemBlockReason: Int? = null,
        rawNumber: String? = null,
        systemScreeningComponent: String? = null,
        systemScreeningAppIsSpamShield: Boolean? = null
    ): Boolean {
        // Outgoing calls establish return-call grace; they are never incoming spam evidence.
        if (callType == android.provider.CallLog.Calls.OUTGOING_TYPE) return false
        val currentSettings = settingsDataStore.getSnapshot()
        val effectiveNormalized = PhoneNormalizer.normalize(rawNumber ?: normalizedNumber, currentSettings.userDdd)
            .normalizedNumber.ifEmpty { normalizedNumber }
        val latest = if (callTimestampMs != null) {
            val match = findScreenedCall(effectiveNormalized, callTimestampMs)
            if (match.deferred) return false
            match.row
        } else {
            dao.getLatestTelemetryForNumber(effectiveNormalized)
        }
        val candidateAlreadyBound = latest != null && callTimestampMs != null &&
            dao.getDecisionEvents(latest.id).any {
                it.eventType == "CALL_LOG_OBSERVATION" &&
                    DecisionTrace.details(it).observedCallTimestamp?.let { ts -> ts != callTimestampMs } == true
            }
        // A different CallLog timestamp is a distinct call, even inside the 15s lookup window.
        // Keep the earlier call's evidence and persist the unpaired redial with its own identity.
        var insertedHistory = false
        val target = latest?.takeUnless { candidateAlreadyBound } ?: run {
            val n = PhoneNormalizer.normalize(rawNumber ?: effectiveNormalized, currentSettings.userDdd)
            val callAt = callTimestampMs ?: nowMillis
            val id = UUID.nameUUIDFromBytes("calllog-$callAt-${n.normalizedNumber}".toByteArray()).toString()
            dao.getTelemetryById(id) ?: CallTelemetryEntity(
                id = id, timestamp = callAt, deviceId = "s23-ultra-history", rawNumber = rawNumber ?: normalizedNumber,
                normalizedNumber = n.normalizedNumber, ddd = n.ddd, prefix7 = n.storedPrefix7.orEmpty(),
                carrierGuess = n.carrierHint, lineType = n.lineType, stirShakenStatus = "NOT_VERIFIED",
                finalScore = 0, thresholdApplied = currentSettings.blockThresholdScore,
                outcome = "ALLOWED_LOW_SCORE", category = "UNKNOWN_UNVERIFIED", processingLatencyMs = 0.0,
                shadowModeActive = false, callDurationSec = callDurationSec, triggeredRulesJson = "[]"
            ).also {
                dao.insertTelemetry(it)
                insertedHistory = true
            }
        }
        DecisionTrace.record(dao, target.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = callType, durationSec = callDurationSec,
                rawNumber = rawNumber, normalizedNumber = normalizedNumber,
                systemBlockReason = systemBlockReason, observedCallTimestamp = callTimestampMs,
                systemScreeningComponent = systemScreeningComponent,
                systemScreeningAppIsSpamShield = systemScreeningAppIsSpamShield),
            at = nowMillis, key = "$callTimestampMs|$callType|$callDurationSec|$systemBlockReason|$systemScreeningComponent")
        val evaluatedRules = dao.observeAllRules().first()
        val changed = applyPostCallLogVerdict(target, normalizedNumber, callType, callDurationSec,
            nowMillis, evaluatedRules)
        val after = dao.getTelemetryById(target.id) ?: target
        DecisionTrace.record(dao, target.id, "POST_CALL_EVALUATION", "POST_CALL_ANALYZER",
            DecisionTrace.configuredSnapshot(after, evaluatedRules).copy(reason = "CallLog type=$callType; blockReason=$systemBlockReason"), at = nowMillis)
        TelemetrySyncScheduler.enqueueImmediateSync()
        return changed || insertedHistory
    }

    internal data class ScreeningMatch(val row: CallTelemetryEntity?, val deferred: Boolean = false)

    internal suspend fun findScreenedCall(normalizedNumber: String, callTimestamp: Long): ScreeningMatch {
        suspend fun findStored() = dao.findRealTimeTelemetryNearTimestamp(
            normalizedNumber,
            callTimestamp - TelemetryCoalescer.WINDOW_MS,
            callTimestamp + TelemetryCoalescer.WINDOW_MS
        )
        val pending = pendingScreeningAudits.asSequence()
            .filter { it.normalizedNumber == normalizedNumber }
            .minByOrNull { abs(it.timestamp - callTimestamp) }
        val stored = findStored()
        if (pending != null) {
            val pendingDistance = abs(pending.timestamp - callTimestamp)
            val storedDistance = stored?.let { abs(it.timestamp - callTimestamp) } ?: Long.MAX_VALUE
            if (pendingDistance <= PENDING_CALL_LOG_WINDOW_MS && pendingDistance < storedDistance) {
                // Telecom can emit CallLog as soon as it receives our response, before Room commits the audit.
                if (withTimeoutOrNull(SCREENING_AUDIT_WAIT_MS) { pending.persisted.await() } == null) {
                    pending.retryCallLog.set(true)
                    if (!pending.persisted.isCompleted) return ScreeningMatch(null, deferred = true)
                }
                return ScreeningMatch(findStored())
            }
        }
        return ScreeningMatch(stored)
    }

    private suspend fun applyPostCallLogVerdict(
        latest: CallTelemetryEntity,
        normalizedNumber: String,
        callType: Int,
        callDurationSec: Int,
        nowMillis: Long,
        configuration: Collection<ActiveRuleEntity>
    ): Boolean {
        val currentDuration = latest.callDurationSec

        if (callType == android.provider.CallLog.Calls.BLOCKED_TYPE ||
            latest.userFeedback != "UNRATED" ||
            latest.outcome == "ALLOWED_CONTACT" || latest.outcome == "ALLOWED_OUTBOUND_GRACE") {
            if (currentDuration == null && callDurationSec >= 0) {
                dao.updateCallDurationAndCategory(latest.id, callDurationSec, latest.category)
            }
            return false
        }

        val existingRules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(latest.triggeredRulesJson)
        }.getOrDefault(emptyList())

        val allRules = configuration
        val ruleWeights = allRules.associate { it.ruleId to it.weight }
        val disabledRuleIds = allRules.filterNot { it.enabled }.map { it.ruleId }.toSet()
        val arrivalEvents = dao.getDecisionEvents(latest.id)
        val arrival = arrivalEvents.firstOrNull { it.eventType == "SCREENING_DECISION" && it.source == "CALL_SCREENING_SERVICE" }
            ?.let(DecisionTrace::details)
        val cleanArrivalRules = (arrival?.rules ?: existingRules).filter {
            it.rule_id != "SYSTEM_CALLLOG_BLOCKED" &&
                it.rule_id != "PING_CALL_PENALTY" &&
                it.rule_id != "CONVERSATION_ANSWERED_BONUS" &&
                it.rule_id != "PREFIX6_CLUSTER_HIT"
        }
        val cleanArrivalScore = if (arrival != null || cleanArrivalRules.isNotEmpty()) {
            HeuristicScorer.equation(cleanArrivalRules)
        } else {
            latest.finalScore
        }

        if ((arrival?.outcome ?: latest.outcome) == "BLOCKED" && callDurationSec > 0) {
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
                    ruleWeights = ruleWeights,
                    disabledRuleIds = disabledRuleIds,
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

        if (callType == android.provider.CallLog.Calls.REJECTED_TYPE && cleanArrivalScore < latest.thresholdApplied) {
            val updatedRules = cleanArrivalRules.toMutableList()
            updatedRules.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", 45, "Rejeitado no Discador"))
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
                    ruleWeights = ruleWeights,
                    disabledRuleIds = disabledRuleIds,
                    callDurationSec = callDurationSec
                )
            )

            val qPrefix = extractQuarantinePrefix(
            latest.normalizedNumber,
            latest.prefix7,
            latest.ddd.takeIf { it.length == 2 } ?: settingsDataStore.getSnapshot().userDdd
        )
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
            if (shouldQuarantine) {
                learnFromCall(latest, qPrefix, "DIALER_REJECTED", QuarantinePrefixIndex.EXACT_WEIGHT, nowMillis)
            }
            return rulesChanged
        }

        // 2. Chamada atendida: 1-5s ping, 6-11s robocall curto, >= 12s bônus
        if (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec in 1..11) {
            return evaluatePostCallPingDetector(normalizedNumber, callDurationSec, nowMillis, latest.id, allRules)
        }

        // 3. Chamada Perdida / Não Atendida (MISSED_TYPE ou duration == 0)
        val isUnanswered = callType == android.provider.CallLog.Calls.MISSED_TYPE ||
            (callType == android.provider.CallLog.Calls.INCOMING_TYPE && callDurationSec == 0)

        val silenceRequested = arrivalEvents.any { it.source == "CALL_SCREENING_SERVICE" &&
            it.eventType == "SCREENING_RESPONSE_SUBMITTED" && DecisionTrace.details(it).silenceCall == true }
        if (isUnanswered && !silenceRequested && cleanArrivalScore < latest.thresholdApplied) {
            val qPrefix = extractQuarantinePrefix(
            latest.normalizedNumber,
            latest.prefix7,
            latest.ddd.takeIf { it.length == 2 } ?: settingsDataStore.getSnapshot().userDdd
        )
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
                return learnFromCall(latest, qPrefix, "UNANSWERED_SPAM_BURST", 65, nowMillis)
            }
        }

        // 4. Conversa atendida (>= 12s) recebe o bônus e o score é a soma das regras
        if (callDurationSec >= 12 && currentDuration == null) {
            val rules = existingRules.toMutableList()
            HeuristicScorer.durationRule(callDurationSec)?.let { band ->
                if (rules.none { it.rule_id == band.rule_id }) rules.add(band)
            }
            val effectiveRules = HeuristicScorer.configuredRules(rules, ScoreInput(rawNumber = latest.rawNumber,
                ruleWeights = ruleWeights, disabledRuleIds = disabledRuleIds))
            val recomputed = HeuristicScorer.equation(effectiveRules)
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
                    category = HeuristicScorer.resolveCategory(latest.stirShakenStatus, answeredOutcome, effectiveRules),
                    triggeredRulesJson = json.encodeToString(effectiveRules),
                    syncStatus = "PENDING"
                )
            )
        }

        return false
    }

    private suspend fun learnFromCall(
        row: CallTelemetryEntity,
        prefix: String,
        reason: String,
        weight: Int,
        observedAt: Long,
        source: String = "POST_CALL_ANALYZER"
    ): Boolean {
        if (prefix.length != 8 || row.shadowModeActive || row.userFeedback == "FALSE_POSITIVE") return false
        if (settingsDataStore.getSnapshot().shadowModeEnabled) return false
        val evidenceAt = minOf(row.timestamp.takeIf { it > 0L } ?: observedAt, observedAt)
        val quarantine = PrefixQuarantineEntity(
            prefix7 = prefix,
            ddd = row.ddd,
            carrierGuess = row.carrierGuess ?: "Outros",
            distinctNumbersJson = json.encodeToString(listOf(row.normalizedNumber)),
            hitCount = 1,
            penaltyWeight = weight,
            reason = reason,
            firstSeenAt = evidenceAt,
            lastSeenAt = evidenceAt,
            expiresAt = evidenceAt + QUARANTINE_DEFAULT_TTL_MS
        )
        if (!dao.installAutomaticQuarantine(quarantine, observedAt)) return false
        QuarantinePrefixIndex.refreshActive(observedAt) { dao.getAllQuarantines() }
        DecisionTrace.record(dao, row.id, "QUARANTINE_UPDATED", source,
            DecisionDetails(prefix = prefix, reason = "$reason; expiresAt=${quarantine.expiresAt}"), at = observedAt)
        return true
    }

    private fun buildShortCircuitResult(
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
        nowMillis: Long,
        settings: br.com.spamshield.data.local.SpamShieldSettings,
        timing: ScreeningTiming?
    ): PreparedScreening {
        timing?.mark("CONTEXT_READY")
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
        val result = CallEvaluationResult(
            telemetry = entity,
            shouldDisallowAndReject = false,
            shouldSkipNotification = false,
            shouldSkipCallLog = false,
            shouldNotifyShadowBlock = false
        )
        timing?.mark("RULES_READY")
        return PreparedScreening(result, settings.expectedWindow) {
            DecisionTrace.snapshot(entity).copy(ruleSetFingerprint = DecisionTrace.digest(
                "protected|$ruleId|$threshold|$shadowMode|${settings.userDdd}"
            ))
        }
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
