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
            dao.deduplicateHistoryRecords()
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

                val realTimeMatch = dao.findRealTimeTelemetryNearTimestamp(
                    normalizedNumber = norm.normalizedNumber,
                    minTs = call.timestamp - 15_000L,
                    maxTs = call.timestamp + 15_000L
                )

                val deterministicId = realTimeMatch?.id ?: UUID.nameUUIDFromBytes(
                    "calllog-${call.timestamp}-${norm.normalizedNumber}".toByteArray()
                ).toString()
                val effectiveTimestamp = realTimeMatch?.timestamp ?: call.timestamp
                val effectiveDeviceId = realTimeMatch?.deviceId ?: "s23-ultra-history"

                val existing = realTimeMatch ?: dao.getTelemetryById(deterministicId)
                val preservedFeedback = existing?.userFeedback ?: "UNRATED"
                val preservedSyncStatus = if (realTimeMatch != null) "PENDING" else (existing?.syncStatus ?: "PENDING")

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

                val inContacts = contactCache[norm.normalizedNumber] ?: false
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

                if (resolvedLineType == "FIXED_OR_VOIP" ||
                    resolvedLineType == "TOLL_FREE_OR_SPECIAL" ||
                    anatelInfo?.isCorporatePabxRange == true
                ) {
                    score += 35
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "ANATEL_PABX_VOIP_RANGE",
                            weight = 35,
                            meta = carrierGuess
                        )
                    )
                }

                if (norm.ddd.isNotEmpty() && norm.ddd != "00" && norm.ddd != settings.userDdd) {
                    score += 30
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "EXTERNAL_DDD_UNKNOWN",
                            weight = 30,
                            meta = "DDD ${norm.ddd} != ${settings.userDdd}"
                        )
                    )
                }

                if (call.rawNumber.trim().startsWith("0")) {
                    score += 25
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "LEADING_ZERO_TRUNK_DIALING",
                            weight = 25,
                            meta = call.rawNumber.trim().take(4)
                        )
                    )
                }

                val isSpam = preservedFeedback == "CONFIRMED_SPAM" ||
                    (preservedFeedback != "FALSE_POSITIVE" &&
                        (score >= settings.blockThresholdScore || wasBlockedOrRejectedInSystem || isPingCall))
                val outcome = when {
                    preservedFeedback == "FALSE_POSITIVE" -> "ALLOWED_CONTACT"
                    isSpam -> "BLOCKED"
                    else -> "ALLOWED_LOW_SCORE"
                }
                val category = when {
                    preservedFeedback == "FALSE_POSITIVE" -> "LEGITIMATE"
                    isPingCall -> "PING_CALL_ROBOCALL"
                    matchedRegexId == "REGEX_TELEMARKETING_NATIONAL" || matchedRegexId == "ANATEL_ATO_10413_0303" -> "KNOWN_BLACKLIST"
                    isSpam -> "ROBOCALL_PREFIX_CLUSTER"
                    else -> "UNKNOWN_UNVERIFIED"
                }

                val latencyMs = ((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(120_000L)) / 1_000_000.0

                dao.insertTelemetry(
                    CallTelemetryEntity(
                        id = deterministicId,
                        timestamp = effectiveTimestamp,
                        deviceId = effectiveDeviceId,
                        rawNumber = call.rawNumber,
                        normalizedNumber = norm.normalizedNumber,
                        ddd = norm.ddd,
                        prefix7 = norm.prefix7,
                        carrierGuess = carrierGuess,
                        lineType = resolvedLineType,
                        stirShakenStatus = "NOT_VERIFIED",
                        finalScore = if (preservedFeedback == "CONFIRMED_SPAM") maxOf(score, 100) else score,
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

    /**
     * Reprocessa retroativamente todo o histórico armazenado no SQLite (call_telemetry e prefix_quarantine),
     * aplicando:
     * 1. Separação estrita entre "VoIP" e "Fixo" (STFC geográfico vs PABX/SIP/Não-geográfico).
     * 2. Correção de CSP sobrepondo números VoIP/Fixo (ex: 0411140043535 -> VoIP) e 00+CSP (004131939995971 -> TIM).
     * 3. Normalização de operadoras legadas ("TIM S.A." -> "TIM", 937..939/980 -> "Claro", 03110315 -> "VoIP").
     * 4. Reavaliação de chamadas que escaparam por ALLOWED_EMERGENCY_REPEAT indevido ou ALLOWED_LOW_SCORE antigo.
     */
    suspend fun reprocessAllStoredTelemetry(): Int = withContext(Dispatchers.IO) {
        dao.deduplicateHistoryRecords()
        val settings = settingsDataStore.getSnapshot()
        val now = System.currentTimeMillis()
        val enabledRules = dao.getEnabledRules().associateBy { it.ruleId }
        val regexRules = enabledRules.values.filter {
            it.ruleType == "REGEX_MATCH" && !it.pattern.isNullOrBlank()
        }.mapNotNull { rule ->
            runCatching { Regex(rule.pattern!!) to rule }.getOrNull()
        }

        // 1. Atualizar tabela de quarentenas (prefix_quarantine)
        val allQuarantines = dao.getAllQuarantines()
        for (q in allQuarantines) {
            val isFixed = q.prefix7.drop(2).firstOrNull() in '2'..'5' ||
                q.prefix7.startsWith("000") ||
                q.prefix7.startsWith("030") ||
                q.prefix7.startsWith("080")
            val newCarrier = PhoneNormalizer.normalizeCarrierName(
                rawCarrier = q.carrierGuess,
                lineType = if (isFixed) "FIXED_OR_VOIP" else "MOBILE",
                isCorporatePabx = false,
                prefix7 = q.prefix7
            )
            if (newCarrier != q.carrierGuess) {
                dao.upsertQuarantine(q.copy(carrierGuess = newCarrier))
            }
        }

        // 2. Reprocessar todas as chamadas em call_telemetry
        val allTelemetry = dao.getAllTelemetry().sortedBy { it.timestamp }
        val prefixDistinctNumbers = mutableMapOf<String, MutableSet<String>>()
        for (row in allTelemetry) {
            val norm = PhoneNormalizer.normalize(row.rawNumber, settings.userDdd)
            if (norm.prefix7.isNotEmpty() && norm.normalizedNumber.isNotEmpty() && row.outcome != "ALLOWED_CONTACT") {
                prefixDistinctNumbers.getOrPut(norm.prefix7) { mutableSetOf() }.add(norm.normalizedNumber)
            }
        }

        var updatedCount = 0
        for (row in allTelemetry) {
            val norm = PhoneNormalizer.normalize(row.rawNumber, settings.userDdd)
            val effectiveNormalized = norm.normalizedNumber.ifEmpty { row.normalizedNumber }
            val effectiveDdd = norm.ddd.ifEmpty { row.ddd }
            val effectivePrefix7 = norm.prefix7.ifEmpty { row.prefix7 }

            val anatelInfo = if (effectivePrefix7.isNotEmpty()) {
                dao.getAnatelPrefix(effectivePrefix7)
            } else {
                null
            }
            val resolvedLineType = anatelInfo?.lineType ?: norm.lineType
            val newCarrierGuess = when {
                anatelInfo != null -> PhoneNormalizer.normalizeCarrierName(
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

            // Se é contato da agenda ou falso positivo ou grace period, apenas atualiza metadados normalizados
            if (row.outcome == "ALLOWED_CONTACT" ||
                row.outcome == "ALLOWED_OUTBOUND_GRACE" ||
                row.userFeedback == "FALSE_POSITIVE"
            ) {
                val updatedRow = row.copy(
                    normalizedNumber = effectiveNormalized,
                    ddd = effectiveDdd,
                    prefix7 = effectivePrefix7,
                    carrierGuess = newCarrierGuess,
                    lineType = resolvedLineType,
                    outcome = if (row.userFeedback == "FALSE_POSITIVE") "ALLOWED_CONTACT" else row.outcome,
                    category = "LEGITIMATE",
                    finalScore = 0
                )
                if (updatedRow != row) {
                    dao.insertTelemetry(updatedRow.copy(syncStatus = "PENDING"))
                    updatedCount++
                }
                continue
            }

            // Verificar se a chamada precisa de recálculo completo de score/regras
            val existingRules = runCatching {
                json.decodeFromString<List<TriggeredRuleDto>>(row.triggeredRulesJson)
            }.getOrDefault(emptyList())

            val wasSystemBlocked = existingRules.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
            val wasPingCall = existingRules.any { it.rule_id == "PING_CALL_PENALTY" } ||
                row.category == "PING_CALL_ROBOCALL"

            var score = 0
            val triggered = mutableListOf<TriggeredRuleDto>()

            val notInContactsW = enabledRules["NOT_IN_CONTACTS"]?.weight ?: 25
            score += notInContactsW
            triggered.add(TriggeredRuleDto("NOT_IN_CONTACTS", notInContactsW, null))

            when (row.stirShakenStatus) {
                "FAILED" -> {
                    val w = enabledRules["STIR_SHAKEN_FAILED"]?.weight ?: 85
                    score += w
                    triggered.add(TriggeredRuleDto("STIR_SHAKEN_FAILED", w, "VERIFICATION_STATUS_FAILED"))
                }
                "", "NOT_VERIFIED" -> {
                    val w = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                    score += w
                    triggered.add(
                        TriggeredRuleDto(
                            "STIR_SHAKEN_UNVERIFIED",
                            w,
                            if (row.deviceId == "s23-ultra-history") "Histórico" else "VERIFICATION_STATUS_NOT_VERIFIED"
                        )
                    )
                }
            }

            if (wasSystemBlocked) {
                val sysRule = existingRules.firstOrNull { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
                val w = sysRule?.weight ?: 45
                score += w
                triggered.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", w, sysRule?.meta ?: "Rejeitado"))
            }

            if (wasPingCall) {
                val pingW = enabledRules["PING_CALL_PENALTY"]?.weight ?: 55
                score += pingW
                triggered.add(TriggeredRuleDto("PING_CALL_PENALTY", pingW, "Duração ${row.callDurationSec}s"))
            }

            val activeQ = if (effectivePrefix7.isNotEmpty()) {
                dao.getActiveQuarantineForPrefix(effectivePrefix7, now)
            } else {
                null
            }
            val distinctInCluster = prefixDistinctNumbers[effectivePrefix7]?.size ?: 0

            if (activeQ != null) {
                score += activeQ.penaltyWeight
                triggered.add(TriggeredRuleDto("PREFIX_QUARANTINE_HIT", activeQ.penaltyWeight, effectivePrefix7))
            } else if (distinctInCluster >= 2) {
                val burstW = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                score += burstW
                triggered.add(
                    TriggeredRuleDto(
                        "PREFIX_NEIGHBORHOOD_BURST",
                        burstW,
                        "$distinctInCluster números no prefixo $effectivePrefix7"
                    )
                )
            } else if (effectivePrefix7.length >= 6) {
                val prefix6 = effectivePrefix7.take(6)
                val q6Count = dao.countActiveQuarantinesByPrefix6(prefix6, now)
                if (q6Count > 0) {
                    score += 50
                    triggered.add(TriggeredRuleDto("PREFIX6_CLUSTER_HIT", 50, "Sub-bloco ${prefix6}x"))
                }
            }

            val prefix5 = effectivePrefix7.take(5)
            if (prefix5.length == 5) {
                val q5Count = dao.countActiveQuarantinesByPrefix5(prefix5, now)
                if (q5Count >= 2 && activeQ == null) {
                    score += 35
                    triggered.add(TriggeredRuleDto("PREFIX5_CHIPEIRA_FARM", 35, "Bloco ${prefix5}xx"))
                }
            }

            var matchedRegexId: String? = null
            for ((rx, rule) in regexRules) {
                if (rx.matches(row.rawNumber) ||
                    rx.matches(effectiveNormalized) ||
                    rx.matches(norm.nationalDigits)
                ) {
                    score += rule.weight
                    matchedRegexId = rule.ruleId
                    triggered.add(TriggeredRuleDto(rule.ruleId, rule.weight, rule.pattern))
                }
            }

            if (resolvedLineType == "FIXED_OR_VOIP" ||
                resolvedLineType == "TOLL_FREE_OR_SPECIAL" ||
                anatelInfo?.isCorporatePabxRange == true
            ) {
                score += 35
                triggered.add(TriggeredRuleDto("ANATEL_PABX_VOIP_RANGE", 35, newCarrierGuess))
            }

            if (effectiveDdd.isNotEmpty() && effectiveDdd != "00" && effectiveDdd != settings.userDdd) {
                score += 30
                triggered.add(TriggeredRuleDto("EXTERNAL_DDD_UNKNOWN", 30, "DDD $effectiveDdd != ${settings.userDdd}"))
            }

            if (row.rawNumber.trim().startsWith("0")) {
                score += 25
                triggered.add(TriggeredRuleDto("LEADING_ZERO_TRUNK_DIALING", 25, row.rawNumber.trim().take(4)))
            }

            if (row.userFeedback == "CONFIRMED_SPAM" && score < 100) {
                score = 100
            }

            val isSpam = row.userFeedback == "CONFIRMED_SPAM" ||
                score >= settings.blockThresholdScore ||
                wasSystemBlocked ||
                wasPingCall

            val newOutcome = if (isSpam) "BLOCKED" else "ALLOWED_LOW_SCORE"
            val newCategory = when {
                row.stirShakenStatus == "FAILED" -> "SPOOFING_STIR_FAILED"
                wasPingCall -> "PING_CALL_ROBOCALL"
                matchedRegexId == "REGEX_TELEMARKETING_NATIONAL" || matchedRegexId == "ANATEL_ATO_10413_0303" -> "KNOWN_BLACKLIST"
                isSpam -> "ROBOCALL_PREFIX_CLUSTER"
                else -> "UNKNOWN_UNVERIFIED"
            }

            val newRulesJson = json.encodeToString(triggered)

            val updatedRow = row.copy(
                normalizedNumber = effectiveNormalized,
                ddd = effectiveDdd,
                prefix7 = effectivePrefix7,
                carrierGuess = newCarrierGuess,
                lineType = resolvedLineType,
                finalScore = score,
                outcome = newOutcome,
                category = newCategory,
                triggeredRulesJson = newRulesJson
            )

            if (updatedRow != row) {
                dao.insertTelemetry(updatedRow.copy(syncStatus = "PENDING"))
                updatedCount++
            }

            if (isSpam && effectivePrefix7.length >= 6) {
                val allNums = prefixDistinctNumbers[effectivePrefix7]?.toList() ?: listOf(effectiveNormalized)
                val existingQ = dao.getActiveQuarantineForPrefix(effectivePrefix7, 0L)
                if (existingQ == null || existingQ.carrierGuess != newCarrierGuess) {
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = effectivePrefix7,
                            ddd = effectiveDdd,
                            carrierGuess = newCarrierGuess,
                            distinctNumbersJson = json.encodeToString(allNums),
                            hitCount = maxOf((existingQ?.hitCount ?: 0), allNums.size, 1),
                            penaltyWeight = existingQ?.penaltyWeight ?: if (wasPingCall) 65 else 70,
                            reason = newCategory,
                            firstSeenAt = minOf(existingQ?.firstSeenAt ?: row.timestamp, row.timestamp),
                            lastSeenAt = maxOf(existingQ?.lastSeenAt ?: row.timestamp, row.timestamp),
                            expiresAt = maxOf(existingQ?.expiresAt ?: 0L, now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS)
                        )
                    )
                }
            }
        }

        if (updatedCount > 0) {
            SyncWorker.enqueueOneTimeSync(context)
        }
        updatedCount
    }
}
