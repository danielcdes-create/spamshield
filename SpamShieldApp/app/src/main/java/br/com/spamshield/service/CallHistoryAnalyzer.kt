package br.com.spamshield.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.CallLog
import androidx.core.content.ContextCompat
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.domain.DecisionTrace
import br.com.spamshield.domain.DecisionDetails
import br.com.spamshield.domain.CompiledRegexRule
import br.com.spamshield.domain.ContactAndCallLogLookup
import br.com.spamshield.domain.HistoryCallScorer
import br.com.spamshield.domain.PhoneNormalizer
import br.com.spamshield.domain.PrefixQuarantineMatch
import br.com.spamshield.domain.QuarantinePrefixIndex
import br.com.spamshield.domain.ScoreInput
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.domain.TelemetryCoalescer
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CallHistoryImportResult(
    val importedCount: Int,
    val spamDetectedCount: Int,
    val quarantinedPrefixesCount: Int,
    val message: String
)

private data class RawSystemCall(
    val rawNumber: String,
    val timestamp: Long,
    val durationSec: Int,
    val callType: Int,
    val blockReason: Int? = null,
    val screeningComponent: String? = null
)

@Singleton
class CallHistoryAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore,
    private val contactLookup: ContactAndCallLogLookup,
    private val spamScoringEngine: SpamScoringEngine
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importAndAnalyzeSystemCallLog(maxRecords: Int = 300): CallHistoryImportResult =
        withContext(Dispatchers.IO) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPerm) {
                return@withContext CallHistoryImportResult(
                    importedCount = 0,
                    spamDetectedCount = 0,
                    quarantinedPrefixesCount = 0,
                    message = "Permissão READ_CALL_LOG não concedida."
                )
            }

            val rawCalls = mutableListOf<RawSystemCall>()
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.BLOCK_REASON,
                        CallLog.Calls.CALL_SCREENING_COMPONENT_NAME
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    var count = 0
                    while (cursor.moveToNext() && count < maxRecords) {
                        val number = cursor.getString(0) ?: ""
                        val date = cursor.getLong(1)
                        val duration = cursor.getInt(2)
                        val type = cursor.getInt(3)
                        if (number.isNotBlank()) {
                            rawCalls.add(RawSystemCall(number, date, duration, type,
                                if (cursor.isNull(4)) null else cursor.getInt(4),
                                if (cursor.isNull(5)) null else cursor.getString(5)))
                            count++
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext CallHistoryImportResult(
                    importedCount = 0,
                    spamDetectedCount = 0,
                    quarantinedPrefixesCount = 0,
                    message = "Erro ao ler histórico: ${e.localizedMessage}"
                )
            }

            if (rawCalls.isEmpty()) {
                return@withContext CallHistoryImportResult(
                    importedCount = 0,
                    spamDetectedCount = 0,
                    quarantinedPrefixesCount = 0,
                    message = "Nenhuma chamada encontrada no histórico do aparelho."
                )
            }

            val settings = settingsDataStore.getSnapshot()
            dao.deduplicateHistoryRecords()
            val allRules = dao.observeAllRules().first()
            val enabledRules = allRules.filter { it.enabled }.associateBy { it.ruleId }
            val regexRules = enabledRules.values.filter {
                it.ruleType == "REGEX_MATCH" && !it.pattern.isNullOrBlank()
            }.mapNotNull { rule ->
                br.com.spamshield.domain.ScreeningPatternCache.patternOrNull(rule.pattern!!)?.let { it to rule }
            }
            val now = System.currentTimeMillis()

            // Process chronologically (oldest to newest), one row per number and timestamp.
            val chronological = TelemetryCoalescer.dedupeSameTimestamp(
                rawCalls.reversed().map { call ->
                    val norm = PhoneNormalizer.normalize(call.rawNumber, settings.userDdd)
                    br.com.spamshield.data.local.entity.CallTelemetryEntity(
                        id = "history-${call.timestamp}-${norm.normalizedNumber}",
                        timestamp = call.timestamp,
                        rawNumber = call.rawNumber,
                        normalizedNumber = norm.normalizedNumber.ifEmpty { call.rawNumber },
                        ddd = norm.ddd,
                        prefix7 = norm.storedPrefix7.orEmpty(),
                        carrierGuess = norm.carrierHint,
                        lineType = norm.lineType,
                        stirShakenStatus = "NOT_VERIFIED",
                        finalScore = 0,
                        thresholdApplied = settings.blockThresholdScore,
                        outcome = "ALLOWED_LOW_SCORE",
                        category = "UNKNOWN_UNVERIFIED",
                        processingLatencyMs = 0.0,
                        shadowModeActive = false,
                        callDurationSec = call.durationSec,
                        triggeredRulesJson = "[]"
                    )
                }
            ).map { row ->
                rawCalls.first { it.timestamp == row.timestamp && it.rawNumber == row.rawNumber }
            }

            val contactCache = mutableMapOf<String, Boolean>()
            val prefixWindow = HistoryPrefixWindow()
            val knownQuarantines = dao.getAllQuarantines().associateBy { it.prefix7 }
            val compiledRules = regexRules.map { (regex, rule) ->
                CompiledRegexRule(rule.ruleId, rule.weight, rule.pattern.orEmpty(), regex)
            }
            val ruleWeights = allRules.associate { it.ruleId to it.weight }
            val disabledRuleIds = allRules.filterNot { it.enabled }.map { it.ruleId }.toSet()

            var processedCount = 0
            var spamDetectedCount = 0
            val newlyQuarantinedPrefixes = mutableSetOf<String>()

            for (call in chronological) {
                val norm = PhoneNormalizer.normalize(call.rawNumber, settings.userDdd)
                if (norm.normalizedNumber.isEmpty()) continue

                if (call.callType == CallLog.Calls.OUTGOING_TYPE) {
                    if (call.timestamp >= now - SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS) {
                        dao.upsertOutboundGrace(
                            OutboundGraceEntity(
                                normalizedNumber = norm.normalizedNumber,
                                prefix6 = norm.prefix6,
                                expiresAt = call.timestamp + SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS
                            )
                        )
                    }
                    continue
                }

                val storagePrefix = norm.storedPrefix7.orEmpty()
                val inContacts = contactCache[norm.normalizedNumber]
                    ?: contactLookup.isNumberInContacts(norm.normalizedNumber).also {
                        contactCache[norm.normalizedNumber] = it
                    }
                val prefixNumbers = prefixWindow.observe(
                    storagePrefix, norm.normalizedNumber.takeUnless { inContacts }, call.timestamp
                )
                val startNanos = SystemClock.elapsedRealtimeNanos()

                val realTimeMatch = spamScoringEngine.findScreenedCall(norm.normalizedNumber, call.timestamp)
                if (realTimeMatch.deferred) continue

                if (realTimeMatch.row != null) {
                    // Reconcile the existing call; observers must not replace its identity or decision.
                    spamScoringEngine.evaluatePostCallLogVerdict(norm.normalizedNumber, call.callType, call.durationSec, callTimestampMs = call.timestamp, rawNumber = call.rawNumber,
                        systemBlockReason = call.blockReason,
                        systemScreeningComponent = call.screeningComponent,
                        systemScreeningAppIsSpamShield = call.screeningComponent?.let { it.substringBefore('/') == "br.com.spamshield" })
                    processedCount++
                    continue
                }

                val deterministicId = UUID.nameUUIDFromBytes(
                    "calllog-${call.timestamp}-${norm.normalizedNumber}".toByteArray()
                ).toString()
                val effectiveTimestamp = call.timestamp
                val effectiveDeviceId = "s23-ultra-history"

                val existing = dao.getTelemetryById(deterministicId)
                val preservedFeedback = existing?.userFeedback ?: "UNRATED"
                val preservedSyncStatus = existing?.syncStatus ?: "PENDING"

                val anatelInfo = if (norm.prefix7.isNotEmpty()) {
                    dao.getAnatelPrefix(norm.prefix7)
                } else {
                    null
                }
                val resolvedLineType = anatelInfo?.lineType ?: norm.lineType
                val carrierGuess = if (anatelInfo != null) {
                    PhoneNormalizer.normalizeCarrierName(
                        anatelInfo.carrier,
                        resolvedLineType,
                        anatelInfo.isCorporatePabxRange,
                        norm.prefix7
                    )
                } else {
                    norm.carrierHint ?: "Outros"
                }

                if (inContacts) {
                    val latencyMs = ((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(80_000L)) / 1_000_000.0
                    dao.insertTelemetry(
                        CallTelemetryEntity(
                            id = deterministicId,
                            timestamp = effectiveTimestamp,
                            deviceId = effectiveDeviceId,
                            rawNumber = call.rawNumber,
                            normalizedNumber = norm.normalizedNumber,
                            ddd = norm.ddd,
                            prefix7 = storagePrefix,
                            carrierGuess = carrierGuess,
                            lineType = resolvedLineType,
                            stirShakenStatus = "NOT_VERIFIED",
                            finalScore = 0,
                            thresholdApplied = settings.blockThresholdScore,
                            outcome = "ALLOWED_CONTACT",
                            category = "LEGITIMATE",
                            processingLatencyMs = latencyMs,
                            shadowModeActive = false,
                            callDurationSec = call.durationSec,
                            triggeredRulesJson = json.encodeToString(
                                listOf(TriggeredRuleDto("CONTACT_WHITELIST", -1000, "Agenda"))
                            ),
                            userFeedback = preservedFeedback,
                            syncStatus = preservedSyncStatus
                        )
                    )
                    dao.getTelemetryById(deterministicId)?.let { imported ->
                        DecisionTrace.record(dao, imported.id, "HISTORY_ASSESSMENT", "HISTORY_IMPORT", DecisionTrace.configuredSnapshot(imported, allRules))
                        DecisionTrace.record(dao, imported.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
                            DecisionDetails(systemCallType = call.callType, durationSec = call.durationSec,
                                observedCallTimestamp = call.timestamp, systemBlockReason = call.blockReason,
                                systemScreeningComponent = call.screeningComponent,
                                systemScreeningAppIsSpamShield = call.screeningComponent?.let { it.substringBefore('/') == "br.com.spamshield" }),
                                key = "${call.timestamp}|${call.callType}|${call.durationSec}|${call.blockReason}|${call.screeningComponent}")
                    }
                    processedCount++
                    continue
                }

                val wasRejectedInDialer = call.callType == CallLog.Calls.REJECTED_TYPE
                val distinctInCluster = prefixNumbers.size
                val quarantineMatch = if (wasRejectedInDialer) PrefixQuarantineMatch.NONE
                    else historicalQuarantineAt(knownQuarantines, norm.prefix8, call.timestamp)
                val decision = HistoryCallScorer.score(
                    ScoreInput(
                        rawNumber = call.rawNumber,
                        normalizedNumber = norm.normalizedNumber,
                        ddd = norm.ddd,
                        prefix7 = norm.storedPrefix7,
                        subscriberNumber = norm.subscriberNumber,
                        carrierGuess = carrierGuess,
                        lineType = resolvedLineType,
                        stirShakenStatus = "NOT_VERIFIED",
                        historicalUnverified = true,
                        quarantineMatch = quarantineMatch,
                        thresholdApplied = settings.blockThresholdScore,
                        userLocalDdd = settings.userDdd,
                        callDurationSec = call.durationSec.takeIf { it > 0 },
                        cspExtracted = norm.cspExtracted,
                        isShortOrUtilityCode = norm.isShortOrUtilityCode,
                        ruleWeights = ruleWeights,
                        disabledRuleIds = disabledRuleIds,
                        extraRegexRules = compiledRules
                    ),
                    rejectedInDialer = wasRejectedInDialer,
                    distinctNumbers = if (wasRejectedInDialer) 1 else distinctInCluster,
                    userFeedback = preservedFeedback
                )
                val triggered = decision.triggeredRules
                val score = decision.finalScore
                val outcome = decision.outcome
                val category = decision.category
                val isPingCall = triggered.any { it.rule_id == "PING_CALL_PENALTY" }
                val isSpam = outcome == "BLOCKED" ||
                    outcome == "FLAGGED_POST_CALL_QUARANTINED" ||
                    outcome == "USER_BLOCKED_RETROACTIVE"
                val learnedFromRejection = triggered.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }

                val latencyMs = ((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(120_000L)) / 1_000_000.0

                dao.insertTelemetry(
                    CallTelemetryEntity(
                        id = deterministicId,
                        timestamp = effectiveTimestamp,
                        deviceId = effectiveDeviceId,
                        rawNumber = call.rawNumber,
                        normalizedNumber = norm.normalizedNumber,
                        ddd = norm.ddd,
                        prefix7 = storagePrefix,
                        carrierGuess = carrierGuess,
                        lineType = resolvedLineType,
                        stirShakenStatus = "NOT_VERIFIED",
                        finalScore = score,
                        thresholdApplied = settings.blockThresholdScore,
                        outcome = outcome,
                        category = category,
                        processingLatencyMs = latencyMs,
                        shadowModeActive = false,
                        callDurationSec = call.durationSec,
                        triggeredRulesJson = json.encodeToString(triggered),
                        userFeedback = preservedFeedback,
                        syncStatus = preservedSyncStatus
                    )
                )
                dao.getTelemetryById(deterministicId)?.let { imported ->
                    DecisionTrace.record(dao, imported.id, "HISTORY_ASSESSMENT", "HISTORY_IMPORT",
                        DecisionTrace.configuredSnapshot(imported, allRules).copy(
                            distinctNumbers24h = distinctInCluster.takeUnless { wasRejectedInDialer },
                            quarantineMatch = quarantineMatch.name,
                            reason = "CALL_LOG_REVIEW"
                        ))
                    DecisionTrace.record(dao, imported.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
                        DecisionDetails(systemCallType = call.callType, durationSec = call.durationSec,
                            observedCallTimestamp = call.timestamp, systemBlockReason = call.blockReason,
                            systemScreeningComponent = call.screeningComponent,
                            systemScreeningAppIsSpamShield = call.screeningComponent?.let { it.substringBefore('/') == "br.com.spamshield" }),
                            key = "${call.timestamp}|${call.callType}|${call.durationSec}|${call.blockReason}|${call.screeningComponent}")
                }
                processedCount++

                val shouldQuarantinePrefix = isSpam &&
                    !settings.shadowModeEnabled &&
                    storagePrefix.length >= 6 &&
                    preservedFeedback != "FALSE_POSITIVE" &&
                    (distinctInCluster >= 2 ||
                        resolvedLineType != "MOBILE" ||
                        learnedFromRejection ||
                        isPingCall ||
                        preservedFeedback == "CONFIRMED_SPAM" ||
                        PhoneNormalizer.isKnownChipeiraFarmPrefix5(norm.prefix7.take(5)) ||
                        (norm.ddd.isNotEmpty() && norm.ddd != settings.userDdd))

                if (isSpam) spamDetectedCount++
                val processedAt = System.currentTimeMillis()
                val expiresAt = historyQuarantineExpiration(call.timestamp, processedAt)
                if (shouldQuarantinePrefix && expiresAt != null) {
                    val reason = if (learnedFromRejection) "DIALER_REJECTED" else category
                    if (dao.installAutomaticQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = storagePrefix,
                            ddd = norm.ddd,
                            carrierGuess = carrierGuess,
                            distinctNumbersJson = json.encodeToString(prefixNumbers.toList()),
                            hitCount = maxOf(1, prefixNumbers.size),
                            penaltyWeight = if (isPingCall) 65 else 70,
                            reason = reason,
                            firstSeenAt = call.timestamp,
                            lastSeenAt = call.timestamp,
                            expiresAt = expiresAt
                        ), processedAt
                    )) {
                        newlyQuarantinedPrefixes.add(storagePrefix)
                        DecisionTrace.record(dao, deterministicId, "QUARANTINE_UPDATED", "HISTORY_IMPORT",
                            DecisionDetails(prefix = storagePrefix, reason = "$reason; expiresAt=$expiresAt"))
                    }
                }
            }

            if (newlyQuarantinedPrefixes.isNotEmpty()) {
                QuarantinePrefixIndex.refreshActive { dao.getAllQuarantines() }
            }
            reprocessAllStoredTelemetry()

            if (processedCount > 0) {
                SyncWorker.enqueueOneTimeSync(context)
            }

            CallHistoryImportResult(
                importedCount = processedCount,
                spamDetectedCount = spamDetectedCount,
                quarantinedPrefixesCount = newlyQuarantinedPrefixes.size,
                message = "Histórico processado: $processedCount chamadas, $spamDetectedCount spams e ${newlyQuarantinedPrefixes.size} prefixos em quarentena!"
            )
        }

    /** Reavalia o histórico com as regras atuais e o contexto observado na chegada. */
    suspend fun reprocessAllStoredTelemetry(): Int =
        reprocessStoredTelemetry(dao, settingsDataStore) { SyncWorker.enqueueOneTimeSync(context) }
    /**
     * Reconcilia chamadas recentes gravadas em call_telemetry com o CallLog do sistema.
     * Passa o timestamp exato da chamada no CallLog para casar o registro correto.
     */
    suspend fun reconcileRecentCallsWithCallLog(maxRecords: Int = 100): Int = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext 0

        var reconciledCount = 0
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val blockReasonIdx = cursor.getColumnIndex("block_reason")
                val screeningComponentIdx = cursor.getColumnIndex("call_screening_component_name")
                if (numIdx < 0 || typeIdx < 0 || durIdx < 0) return@use

                val now = System.currentTimeMillis()
                var readCount = 0
                val collected = mutableListOf<RawSystemCall>()

                while (cursor.moveToNext() && readCount < maxRecords) {
                    readCount++
                    val rawNumber = cursor.getString(numIdx) ?: continue
                    val callType = cursor.getInt(typeIdx)
                    val durationSec = cursor.getInt(durIdx)
                    val callDate = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val systemBlockReason = if (blockReasonIdx >= 0 && !cursor.isNull(blockReasonIdx)) cursor.getInt(blockReasonIdx) else null
                    val screeningComponent = if (screeningComponentIdx >= 0) cursor.getString(screeningComponentIdx) else null
                    collected.add(
                        RawSystemCall(
                            rawNumber = rawNumber,
                            timestamp = callDate,
                            durationSec = durationSec,
                            callType = callType,
                            blockReason = systemBlockReason,
                            screeningComponent = screeningComponent
                        )
                    )
                }

                val userDdd = settingsDataStore.getSnapshot().userDdd
                for (call in collected.asReversed()) {
                    val norm = PhoneNormalizer.normalize(call.rawNumber, userDdd)
                    if (norm.normalizedNumber.isEmpty()) continue
                    if (call.callType == CallLog.Calls.OUTGOING_TYPE) {
                        val callAt = call.timestamp.takeIf { it > 0L } ?: now
                        if (callAt >= now - SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS) {
                            dao.upsertOutboundGrace(OutboundGraceEntity(
                                normalizedNumber = norm.normalizedNumber,
                                prefix6 = norm.prefix6,
                                expiresAt = callAt + SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS
                            ))
                        }
                        continue
                    }

                    val changed = spamScoringEngine.evaluatePostCallLogVerdict(
                        normalizedNumber = norm.normalizedNumber,
                        callType = call.callType,
                        callDurationSec = call.durationSec,
                        systemBlockReason = call.blockReason,
                        rawNumber = call.rawNumber,
                        systemScreeningComponent = call.screeningComponent,
                        systemScreeningAppIsSpamShield = call.screeningComponent?.let { it.substringBefore('/') == "br.com.spamshield" },
                        callTimestampMs = call.timestamp.takeIf { it > 0L },
                        nowMillis = now
                    )
                    if (changed) {
                        reconciledCount++
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CallHistoryAnalyzer", "Reconcile with CallLog failed: ${e.message}")
        }

        if (reconciledCount > 0) {
            SyncWorker.enqueueOneTimeSync(context)
        }
        reconciledCount
    }
}

internal suspend fun reprocessStoredTelemetry(
    dao: SpamShieldDao,
    settingsDataStore: SettingsDataStore,
    onUpdated: () -> Unit = {}
): Int = withContext(Dispatchers.IO) {
        val json = Json { ignoreUnknownKeys = true }
        val settings = settingsDataStore.getSnapshot()
        val allRules = dao.observeAllRules().first()
        val enabledRules = allRules.filter { it.enabled }.associateBy { it.ruleId }
        val regexRules = enabledRules.values.filter {
            it.ruleType == "REGEX_MATCH" && !it.pattern.isNullOrBlank()
        }.mapNotNull { rule ->
            br.com.spamshield.domain.ScreeningPatternCache.patternOrNull(rule.pattern!!)?.let { it to rule }
        }

        var updatedCount = deduplicateStoredTelemetryRows(dao, settings.userDdd)

        val allTelemetry = dao.getAllTelemetry().sortedBy { it.timestamp }
        val eventsByCall = dao.getAllDecisionEvents().groupBy { it.callId }
        val outgoingOnlyIds = eventsByCall.filterValues { events ->
            events.none { it.eventType == "SCREENING_DECISION" } &&
                events.filter { it.eventType == "CALL_LOG_OBSERVATION" }.let { observations ->
                    observations.isNotEmpty() && observations.all {
                        DecisionTrace.details(it).systemCallType == CallLog.Calls.OUTGOING_TYPE
                    }
                }
        }.keys
        val prefixWindow = HistoryPrefixWindow()
        val knownQuarantines = dao.getAllQuarantines().associateBy { it.prefix7 }
        val compiledRules = regexRules.map { (regex, rule) ->
            CompiledRegexRule(rule.ruleId, rule.weight, rule.pattern.orEmpty(), regex)
        }
        val ruleWeights = allRules.associate { it.ruleId to it.weight }
        val disabledRuleIds = allRules.filterNot { it.enabled }.map { it.ruleId }.toSet()

        for (row in allTelemetry) {
            val priorEvents = eventsByCall[row.id].orEmpty()
            if (row.id in outgoingOnlyIds || row.outcome == "OUTGOING_CALL") {
                val corrected = row.copy(outcome = "OUTGOING_CALL", category = "OUTGOING",
                    finalScore = 0, triggeredRulesJson = "[]")
                DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR",
                    DecisionTrace.snapshot(corrected).copy(reason = "OUTGOING_CALL_LOG_CORRECTION"))
                if (corrected != row) {
                    dao.insertTelemetry(corrected.copy(syncStatus = "PENDING"))
                    updatedCount++
                }
                continue
            }
            val arrivalEvent = originalScreeningDecision(priorEvents)
            val isRealTimeCall = arrivalEvent != null || row.deviceId != "s23-ultra-history"
            val norm = PhoneNormalizer.normalize(row.rawNumber, settings.userDdd)
            val isUtility = norm.isShortOrUtilityCode || norm.lineType == "TOLL_FREE_OR_SPECIAL"
            val effectiveNormalized = norm.normalizedNumber.ifEmpty { row.normalizedNumber }
            val effectiveDdd = norm.ddd.ifEmpty { row.ddd }
            val effectivePrefix7 = if (isUtility) "" else norm.prefix7.ifEmpty { row.prefix7.take(7) }
            val storagePrefix = when {
                isUtility -> ""
                norm.prefix8.length == 8 && (isRealTimeCall || row.prefix7.length == 8) -> norm.prefix8
                else -> effectivePrefix7
            }

            val anatelInfo = if (effectivePrefix7.isNotEmpty()) {
                dao.getAnatelPrefix(effectivePrefix7)
            } else {
                null
            }
            val resolvedLineType = if (isUtility || norm.lineType == "MALFORMED_OR_SPOOFED") {
                norm.lineType
            } else {
                anatelInfo?.lineType ?: norm.lineType
            }
            val newCarrierGuess = when {
                anatelInfo != null && !isUtility && norm.lineType != "MALFORMED_OR_SPOOFED" -> PhoneNormalizer.normalizeCarrierName(
                    anatelInfo.carrier,
                    resolvedLineType,
                    anatelInfo.isCorporatePabxRange,
                    effectivePrefix7
                )
                norm.carrierHint != null -> norm.carrierHint
                else -> PhoneNormalizer.normalizeCarrierName(
                    row.carrierGuess,
                    resolvedLineType,
                    false,
                    effectivePrefix7
                )
            }
            val historicalPrefixNumbers = prefixWindow.observe(
                storagePrefix,
                effectiveNormalized.takeUnless { row.outcome == "ALLOWED_CONTACT" || (row.callDurationSec ?: 0) >= 10 },
                row.timestamp
            ).size

            if (row.outcome == "ALLOWED_CONTACT" ||
                row.outcome == "ALLOWED_OUTBOUND_GRACE" ||
                row.userFeedback == "FALSE_POSITIVE"
            ) {
                val updatedRow = row.copy(
                    normalizedNumber = effectiveNormalized,
                    ddd = effectiveDdd,
                    prefix7 = storagePrefix,
                    carrierGuess = newCarrierGuess,
                    lineType = resolvedLineType,
                    outcome = if (row.userFeedback == "FALSE_POSITIVE") "ALLOWED_CONTACT" else row.outcome,
                    category = "LEGITIMATE",
                    finalScore = 0
                )
                DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR",
                    DecisionTrace.snapshot(updatedRow).copy(reason = "PROTECTED_ALLOW"))
                if (updatedRow != row) {
                    dao.insertTelemetry(updatedRow.copy(syncStatus = "PENDING"))
                    updatedCount++
                }
                continue
            }

            val arrivalRules = arrivalEvent?.rules.orEmpty()
            val observedCall = priorEvents.lastOrNull {
                it.eventType == "CALL_LOG_OBSERVATION" && it.source == "ANDROID_CALL_LOG"
            }?.let(DecisionTrace::details)
            val wasRejectedInDialer = observedCall?.systemCallType == CallLog.Calls.REJECTED_TYPE
            val quarantineMatch = when {
                arrivalEvent != null -> arrivalQuarantineMatch(arrivalEvent)
                isRealTimeCall || wasRejectedInDialer -> PrefixQuarantineMatch.NONE
                else -> historicalQuarantineAt(knownQuarantines, norm.prefix8, row.timestamp)
            }
            val distinctNumbers24h = when {
                arrivalEvent != null -> arrivalDistinctNumbers(arrivalEvent)
                isRealTimeCall || wasRejectedInDialer -> null
                else -> historicalPrefixNumbers
            }
            val distinctForScoring = when {
                distinctNumbers24h != null -> distinctNumbers24h
                arrivalRules.any { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" } -> 2
                else -> 1
            }
            val decision = HistoryCallScorer.score(
                ScoreInput(
                    rawNumber = row.rawNumber,
                    normalizedNumber = effectiveNormalized,
                    ddd = effectiveDdd,
                    prefix7 = norm.storedPrefix7 ?: effectivePrefix7.takeIf { it.length == 8 },
                    subscriberNumber = norm.subscriberNumber,
                    carrierGuess = newCarrierGuess,
                    lineType = resolvedLineType,
                    stirShakenStatus = row.stirShakenStatus,
                    historicalUnverified = !isRealTimeCall,
                    quarantineMatch = quarantineMatch,
                    thresholdApplied = settings.blockThresholdScore,
                    userLocalDdd = settings.userDdd,
                    callDurationSec = row.callDurationSec?.takeIf { it > 0 },
                    cspExtracted = norm.cspExtracted,
                    isShortOrUtilityCode = norm.isShortOrUtilityCode,
                    shadowMode = arrivalEvent?.shadowMode ?: row.shadowModeActive,
                    unverifiedWeight = if (!isRealTimeCall) 15 else (enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20),
                    ruleWeights = ruleWeights,
                    disabledRuleIds = disabledRuleIds,
                    extraRegexRules = compiledRules
                ),
                rejectedInDialer = wasRejectedInDialer,
                distinctNumbers = distinctForScoring,
                userFeedback = row.userFeedback,
                prefix6NeighborRule = arrivalRules.firstOrNull { it.rule_id == "PREFIX6_NEIGHBOR_WARNING" },
                prefix5ChipeiraRule = arrivalRules.firstOrNull { it.rule_id == "PREFIX5_CHIPEIRA_FARM" }
            )
            val triggered = decision.triggeredRules
            val score = decision.finalScore
            val learnedFromRejection = triggered.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
            val newOutcome = decision.outcome
            val newCategory = decision.category
            val newRulesJson = json.encodeToString(triggered)
            val finalPrefix = if (learnedFromRejection && norm.prefix8.length == 8) norm.prefix8 else storagePrefix

            val updatedRow = row.copy(
                normalizedNumber = effectiveNormalized,
                ddd = effectiveDdd,
                prefix7 = finalPrefix,
                carrierGuess = newCarrierGuess,
                lineType = resolvedLineType,
                finalScore = score,
                thresholdApplied = settings.blockThresholdScore,
                shadowModeActive = arrivalEvent?.shadowMode ?: row.shadowModeActive,
                outcome = newOutcome,
                category = newCategory,
                triggeredRulesJson = newRulesJson
            )

            DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR",
                DecisionTrace.configuredSnapshot(updatedRow, allRules).copy(
                    distinctNumbers24h = distinctNumbers24h,
                    quarantineMatch = quarantineMatch.name,
                    reason = "REASSESSMENT_WITH_CURRENT_RULES"
                ))
            if (updatedRow != row) {
                dao.insertTelemetry(updatedRow.copy(syncStatus = "PENDING"))
                updatedCount++
            }
        }

        if (updatedCount > 0) {
            onUpdated()
        }
        updatedCount
    }

private suspend fun deduplicateStoredTelemetryRows(
    dao: SpamShieldDao,
    userDdd: String
): Int {
    var removed = 0
    val byTimestampAndNum = dao.getAllTelemetry().groupBy { row ->
        val norm = PhoneNormalizer.normalize(row.rawNumber, userDdd).normalizedNumber.ifEmpty { row.normalizedNumber }
        row.timestamp to norm
    }
    for ((key, group) in byTimestampAndNum) {
        if (group.size < 2) continue
        val (ts, normNum) = key
        val canonicalHistoryId = UUID.nameUUIDFromBytes("calllog-$ts-$normNum".toByteArray()).toString()
        // Preserve journal ownership even if a previous server accepted a row without an ACK.
        val retainedRows = group.filter {
            it.syncStatus != "PENDING" || it.userFeedback != "UNRATED" || dao.getDecisionEvents(it.id).isNotEmpty()
        }
        val keeper = retainedRows.ifEmpty { group }.maxWithOrNull(
            compareBy<CallTelemetryEntity> { it.deviceId != "s23-ultra-history" }
                .thenBy { it.id == canonicalHistoryId }
                .thenBy { it.finalScore }
        ) ?: group.first()
        for (dup in group) {
            if (dup.id != keeper.id) {
                removed += dao.deleteUnjournaledPendingTelemetry(dup.id)
            }
        }
    }

    return removed
}

private fun originalScreeningDecision(events: List<CallDecisionEventEntity>): DecisionDetails? =
    events.firstOrNull {
        it.eventType == "SCREENING_DECISION" && it.source == "CALL_SCREENING_SERVICE"
    }?.let(DecisionTrace::details)

private fun arrivalQuarantineMatch(arrival: DecisionDetails): PrefixQuarantineMatch =
    when (arrival.quarantineMatch) {
        "EXACT8" -> PrefixQuarantineMatch.EXACT8
        "BROAD7" -> PrefixQuarantineMatch.BROAD7
        "NONE" -> PrefixQuarantineMatch.NONE
        else -> when {
            arrival.rules.any { it.rule_id == "PREFIX_QUARANTINE_HIT" } -> PrefixQuarantineMatch.EXACT8
            arrival.rules.any { it.rule_id == "PREFIX7_BROAD_QUARANTINE_HIT" } -> PrefixQuarantineMatch.BROAD7
            else -> PrefixQuarantineMatch.NONE
        }
    }

private fun arrivalDistinctNumbers(arrival: DecisionDetails): Int? =
    arrival.distinctNumbers24h ?: arrival.rules.firstOrNull { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }
        ?.meta?.substringAfter("distinct_24h=", "")?.substringBefore(' ')?.toIntOrNull()

private fun historicalQuarantineAt(
    knownQuarantines: Map<String, PrefixQuarantineEntity>, prefix8: String, callAt: Long
): PrefixQuarantineMatch {
    if (prefix8.length != 8) return PrefixQuarantineMatch.NONE
    fun wasActive(prefix: String) = knownQuarantines[prefix]?.let {
        it.lastSeenAt <= callAt && callAt < it.expiresAt
    } == true
    return when {
        wasActive(prefix8) -> PrefixQuarantineMatch.EXACT8
        wasActive(prefix8.take(7)) -> PrefixQuarantineMatch.BROAD7
        else -> PrefixQuarantineMatch.NONE
    }
}

internal fun historyQuarantineExpiration(evidenceAt: Long, now: Long): Long? {
    if (evidenceAt <= 0 || evidenceAt > now) return null
    val expiration = evidenceAt + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
    return expiration.takeIf { it > now }
}

/** Keeps counts of distinct callers in the 24 hours ending at each historical call. */
private class HistoryPrefixWindow {
    private data class Call(val prefix: String, val number: String, val at: Long)

    private val calls = ArrayDeque<Call>()
    private val recentByPrefix = mutableMapOf<String, MutableMap<String, Int>>()

    fun observe(prefix: String, number: String?, at: Long): Set<String> {
        val since = at - SpamScoringEngine.NEIGHBORHOOD_BURST_WINDOW_MS
        while (calls.firstOrNull()?.let { it.at < since } == true) {
            val previous = calls.removeFirst()
            val counts = recentByPrefix.getValue(previous.prefix)
            val remaining = counts.getValue(previous.number) - 1
            if (remaining == 0) counts.remove(previous.number) else counts[previous.number] = remaining
            if (counts.isEmpty()) recentByPrefix.remove(previous.prefix)
        }
        if (prefix.isNotEmpty() && !number.isNullOrEmpty()) {
            calls.addLast(Call(prefix, number, at))
            val counts = recentByPrefix.getOrPut(prefix) { mutableMapOf() }
            counts[number] = counts.getOrDefault(number, 0) + 1
        }
        return recentByPrefix[prefix]?.keys.orEmpty()
    }
}
