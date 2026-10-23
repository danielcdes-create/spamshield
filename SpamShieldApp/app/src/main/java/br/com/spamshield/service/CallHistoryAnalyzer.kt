package br.com.spamshield.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.CallLog
import androidx.core.content.ContextCompat
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.domain.ContactAndCallLogLookup
import br.com.spamshield.domain.PhoneNormalizer
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    val callType: Int
)

@Singleton
class CallHistoryAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore,
    private val contactLookup: ContactAndCallLogLookup
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
                        CallLog.Calls.TYPE
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
                            rawCalls.add(RawSystemCall(number, date, duration, type))
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
            val enabledRules = dao.getEnabledRules().associateBy { it.ruleId }
            val regexRules = enabledRules.values.filter {
                it.ruleType == "REGEX_MATCH" && !it.pattern.isNullOrBlank()
            }.mapNotNull { rule ->
                runCatching { Regex(rule.pattern!!) to rule }.getOrNull()
            }
            val now = System.currentTimeMillis()

            // Process chronologically (oldest to newest)
            val chronological = rawCalls.reversed()

            // Pre-group distinct numbers per prefix7 and cache contact lookups per distinct number
            val prefixDistinctNumbers = mutableMapOf<String, MutableSet<String>>()
            val contactCache = mutableMapOf<String, Boolean>()

            for (c in chronological) {
                if (c.callType == CallLog.Calls.OUTGOING_TYPE) continue
                val norm = PhoneNormalizer.normalize(c.rawNumber, settings.userDdd)
                if (norm.prefix7.isNotEmpty() && norm.normalizedNumber.isNotEmpty()) {
                    prefixDistinctNumbers
                        .getOrPut(norm.prefix7) { mutableSetOf() }
                        .add(norm.normalizedNumber)
                    if (!contactCache.containsKey(norm.normalizedNumber)) {
                        contactCache[norm.normalizedNumber] =
                            contactLookup.isNumberInContacts(norm.normalizedNumber)
                    }
                }
            }

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

                // Measure pure engine evaluation latency (excluding batch ContentResolver IPC)
                val startNanos = SystemClock.elapsedRealtimeNanos()

                val deterministicId = UUID.nameUUIDFromBytes(
                    "calllog-${call.timestamp}-${norm.normalizedNumber}".toByteArray()
                ).toString()

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
                        anatelInfo.isCorporatePabxRange
                    )
                } else {
                    norm.carrierHint ?: "Outros"
                }

                val inContacts = contactCache[norm.normalizedNumber] ?: false
                if (inContacts) {
                    val latencyMs = ((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(80_000L)) / 1_000_000.0
                    dao.insertTelemetry(
                        CallTelemetryEntity(
                            id = deterministicId,
                            timestamp = call.timestamp,
                            deviceId = "s23-ultra-history",
                            rawNumber = call.rawNumber,
                            normalizedNumber = norm.normalizedNumber,
                            ddd = norm.ddd,
                            prefix7 = norm.prefix7,
                            carrierGuess = carrierGuess,
                            lineType = resolvedLineType,
                            stirShakenStatus = "PASSED",
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
                    processedCount++
                    continue
                }

                // Calculate heuristic score for non-contact historical call
                var score = 0
                val triggered = mutableListOf<TriggeredRuleDto>()

                val notInContactsW = enabledRules["NOT_IN_CONTACTS"]?.weight ?: 25
                score += notInContactsW
                triggered.add(TriggeredRuleDto("NOT_IN_CONTACTS", notInContactsW, null))

                val unverifiedW = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                score += unverifiedW
                triggered.add(TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", unverifiedW, "Histórico"))

                // System CallLog blocked (6) or rejected (5)
                val wasBlockedOrRejectedInSystem = call.callType == CallLog.Calls.BLOCKED_TYPE ||
                    call.callType == CallLog.Calls.REJECTED_TYPE
                if (wasBlockedOrRejectedInSystem) {
                    score += 45
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "SYSTEM_CALLLOG_BLOCKED",
                            weight = 45,
                            meta = if (call.callType == CallLog.Calls.BLOCKED_TYPE) "Bloqueado no Sistema" else "Rejeitado"
                        )
                    )
                }

                // Ping Call (< 4s answered incoming call)
                val isPingCall = call.callType == CallLog.Calls.INCOMING_TYPE && call.durationSec in 1..4
                if (isPingCall) {
                    val pingW = enabledRules["PING_CALL_PENALTY"]?.weight ?: 55
                    score += pingW
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "PING_CALL_PENALTY",
                            weight = pingW,
                            meta = "Duração ${call.durationSec}s"
                        )
                    )
                }

                // Active Quarantine or Prefix Cluster in History
                val distinctInCluster = prefixDistinctNumbers[norm.prefix7]?.size ?: 0
                val activeQ = if (norm.prefix7.isNotEmpty()) {
                    dao.getActiveQuarantineForPrefix(norm.prefix7, now)
                } else {
                    null
                }

                if (activeQ != null) {
                    score += activeQ.penaltyWeight
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "PREFIX_QUARANTINE_HIT",
                            weight = activeQ.penaltyWeight,
                            meta = norm.prefix7
                        )
                    )
                } else if (distinctInCluster >= 2) {
                    val burstW = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                    score += burstW
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "PREFIX_NEIGHBORHOOD_BURST",
                            weight = burstW,
                            meta = "${distinctInCluster} números no prefixo ${norm.prefix7}"
                        )
                    )
                }

                // Regex rules
                var matchedRegexId: String? = null
                for ((rx, rule) in regexRules) {
                    if (rx.matches(norm.rawNumber) ||
                        rx.matches(norm.normalizedNumber) ||
                        rx.matches(norm.nationalDigits)
                    ) {
                        score += rule.weight
                        matchedRegexId = rule.ruleId
                        triggered.add(TriggeredRuleDto(rule.ruleId, rule.weight, rule.pattern))
                    }
                }

                if (resolvedLineType == "FIXED_OR_VOIP") {
                    score += 35
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "ANATEL_PABX_VOIP_RANGE",
                            weight = 35,
                            meta = carrierGuess
                        )
                    )
                }

                val isSpam = score >= settings.blockThresholdScore || wasBlockedOrRejectedInSystem || isPingCall
                val outcome = if (isSpam) "BLOCKED" else "ALLOWED_LOW_SCORE"
                val category = when {
                    isPingCall -> "PING_CALL_ROBOCALL"
                    matchedRegexId == "REGEX_TELEMARKETING_NATIONAL" || matchedRegexId == "ANATEL_ATO_10413_0303" -> "KNOWN_BLACKLIST"
                    isSpam -> "ROBOCALL_PREFIX_CLUSTER"
                    else -> "UNKNOWN_UNVERIFIED"
                }

                val latencyMs = ((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(120_000L)) / 1_000_000.0

                dao.insertTelemetry(
                    CallTelemetryEntity(
                        id = deterministicId,
                        timestamp = call.timestamp,
                        deviceId = "s23-ultra-history",
                        rawNumber = call.rawNumber,
                        normalizedNumber = norm.normalizedNumber,
                        ddd = norm.ddd,
                        prefix7 = norm.prefix7,
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
                processedCount++

                if (isSpam && norm.prefix7.length >= 6 && preservedFeedback != "FALSE_POSITIVE") {
                    spamDetectedCount++
                    newlyQuarantinedPrefixes.add(norm.prefix7)
                    val allNumsInPrefix = prefixDistinctNumbers[norm.prefix7]?.toList() ?: listOf(norm.normalizedNumber)
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = norm.prefix7,
                            ddd = norm.ddd,
                            carrierGuess = carrierGuess,
                            distinctNumbersJson = json.encodeToString(allNumsInPrefix),
                            hitCount = maxOf((activeQ?.hitCount ?: 0) + 1, allNumsInPrefix.size),
                            penaltyWeight = if (isPingCall) 65 else 70,
                            reason = category,
                            firstSeenAt = minOf(activeQ?.firstSeenAt ?: call.timestamp, call.timestamp),
                            lastSeenAt = maxOf(activeQ?.lastSeenAt ?: call.timestamp, call.timestamp),
                            expiresAt = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
                        )
                    )
                }
            }

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
}
