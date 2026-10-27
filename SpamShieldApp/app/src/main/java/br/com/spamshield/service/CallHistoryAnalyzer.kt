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

            // Pre-group distinct numbers per prefix (/8 and /7) and cache contact lookups
            val prefixDistinctNumbers = mutableMapOf<String, MutableSet<String>>()
            val contactCache = mutableMapOf<String, Boolean>()

            for (c in chronological) {
                if (c.callType == CallLog.Calls.OUTGOING_TYPE) continue
                val norm = PhoneNormalizer.normalize(c.rawNumber, settings.userDdd)
                if (norm.prefix7.isNotEmpty() && norm.normalizedNumber.isNotEmpty()) {
                    prefixDistinctNumbers
                        .getOrPut(norm.prefix7) { mutableSetOf() }
                        .add(norm.normalizedNumber)
                    if (norm.prefix8.length == 8) {
                        prefixDistinctNumbers
                            .getOrPut(norm.prefix8) { mutableSetOf() }
                            .add(norm.normalizedNumber)
                    }
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

                val storagePrefix = if (norm.prefix8.length == 8) norm.prefix8 else norm.prefix7

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
                            prefix7 = storagePrefix,
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

                val existingRules = existing?.let {
                    runCatching { json.decodeFromString<List<TriggeredRuleDto>>(it.triggeredRulesJson) }.getOrDefault(emptyList())
                } ?: emptyList()

                val hadQuarantineAtArrival = existingRules.any { it.rule_id == "PREFIX_QUARANTINE_HIT" }
                val hadBurstAtArrival = existingRules.any { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }
                val wasRejectedInDialer = call.callType == CallLog.Calls.REJECTED_TYPE ||
                    existingRules.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }

                var score = 0
                val triggered = mutableListOf<TriggeredRuleDto>()

                val notInContactsW = enabledRules["NOT_IN_CONTACTS"]?.weight ?: 25
                score += notInContactsW
                triggered.add(TriggeredRuleDto("NOT_IN_CONTACTS", notInContactsW, null))

                val unverifiedW = enabledRules["STIR_SHAKEN_UNVERIFIED"]?.weight ?: 20
                score += unverifiedW
                triggered.add(
                    TriggeredRuleDto(
                        "STIR_SHAKEN_UNVERIFIED",
                        unverifiedW,
                        if (effectiveDeviceId == "s23-ultra-history") "Histórico" else "VERIFICATION_STATUS_NOT_VERIFIED"
                    )
                )

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

                // Quarentena ou cluster: NÃO injeta retroativamente em chamadas que tocaram e foram rejeitadas no discador
                val distinctInCluster = prefixDistinctNumbers[storagePrefix]?.size ?: 0
                val activeQ = if (norm.prefix7.isNotEmpty()) {
                    dao.getActiveQuarantineForPrefix(prefix8 = norm.prefix8, prefix7 = norm.prefix7, now = now)
                } else {
                    null
                }

                val canApplyQuarantineOrBurst = if (realTimeMatch != null) {
                    !wasRejectedInDialer && (hadQuarantineAtArrival || hadBurstAtArrival)
                } else {
                    !wasRejectedInDialer
                }

                if (canApplyQuarantineOrBurst) {
                    if (activeQ != null && (realTimeMatch == null || hadQuarantineAtArrival)) {
                        score += activeQ.penaltyWeight
                        triggered.add(
                            TriggeredRuleDto(
                                rule_id = "PREFIX_QUARANTINE_HIT",
                                weight = activeQ.penaltyWeight,
                                meta = activeQ.prefix7
                            )
                        )
                    } else if (distinctInCluster >= 2 && (realTimeMatch == null || hadBurstAtArrival)) {
                        val burstW = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                        score += burstW
                        triggered.add(
                            TriggeredRuleDto(
                                rule_id = "PREFIX_NEIGHBORHOOD_BURST",
                                weight = burstW,
                                meta = "$distinctInCluster números no prefixo $storagePrefix"
                            )
                        )
                    }
                }

                val prefix5 = norm.prefix7.take(5)
                if (prefix5.length == 5 && PhoneNormalizer.isKnownChipeiraFarmPrefix5(prefix5) && activeQ == null && !wasRejectedInDialer) {
                    score += 35
                    triggered.add(TriggeredRuleDto("PREFIX5_CHIPEIRA_FARM", 35, "Bloco ${prefix5}xx"))
                }

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

                if (PhoneNormalizer.hasAbnormalTrunkOrCspHeader(call.rawNumber, settings.userDdd)) {
                    score += 25
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "LEADING_ZERO_TRUNK_DIALING",
                            weight = 25,
                            meta = call.rawNumber.trim().take(4)
                        )
                    )
                }

                // Aprendizado Ativo: se o score de chegada era < threshold e a chamada foi rejeitada no discador (REJECTED_TYPE)
                // ou bloqueada em chamada que havia passado na borda, aplica SYSTEM_CALLLOG_BLOCKED (+45).
                val wasBlockedOrRejectedAfterRing = score < settings.blockThresholdScore &&
                    (wasRejectedInDialer || (call.callType == CallLog.Calls.BLOCKED_TYPE && realTimeMatch?.outcome == "ALLOWED_LOW_SCORE"))

                if (wasBlockedOrRejectedAfterRing) {
                    score += 45
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "SYSTEM_CALLLOG_BLOCKED",
                            weight = 45,
                            meta = if (call.callType == CallLog.Calls.BLOCKED_TYPE && !wasRejectedInDialer) {
                                "Bloqueado no Sistema"
                            } else {
                                "Rejeitado no Discador"
                            }
                        )
                    )
                }

                if (call.durationSec >= 10 && preservedFeedback != "CONFIRMED_SPAM" && !wasBlockedOrRejectedAfterRing) {
                    score = (score - 40).coerceAtLeast(0)
                    triggered.add(
                        TriggeredRuleDto(
                            rule_id = "CONVERSATION_ANSWERED_BONUS",
                            weight = -40,
                            meta = "Conversa atendida (${call.durationSec}s)"
                        )
                    )
                }

                val isSpam = preservedFeedback == "CONFIRMED_SPAM" ||
                    (preservedFeedback != "FALSE_POSITIVE" &&
                        (score >= settings.blockThresholdScore || isPingCall))
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

                val latencyMs = realTimeMatch?.processingLatencyMs
                    ?: (((SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(120_000L)) / 1_000_000.0)

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
                        stirShakenStatus = realTimeMatch?.stirShakenStatus ?: "NOT_VERIFIED",
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

                val shouldQuarantinePrefix = isSpam &&
                    storagePrefix.length >= 6 &&
                    preservedFeedback != "FALSE_POSITIVE" &&
                    (distinctInCluster >= 2 ||
                        resolvedLineType != "MOBILE" ||
                        wasBlockedOrRejectedAfterRing ||
                        isPingCall ||
                        preservedFeedback == "CONFIRMED_SPAM" ||
                        PhoneNormalizer.isKnownChipeiraFarmPrefix5(norm.prefix7.take(5)) ||
                        (norm.ddd.isNotEmpty() && norm.ddd != settings.userDdd))

                if (shouldQuarantinePrefix) {
                    spamDetectedCount++
                    newlyQuarantinedPrefixes.add(storagePrefix)
                    val allNumsInPrefix = prefixDistinctNumbers[storagePrefix]?.toList()
                        ?: prefixDistinctNumbers[norm.prefix7]?.toList()
                        ?: listOf(norm.normalizedNumber)
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = storagePrefix,
                            ddd = norm.ddd,
                            carrierGuess = carrierGuess,
                            distinctNumbersJson = json.encodeToString(allNumsInPrefix),
                            hitCount = maxOf((activeQ?.hitCount ?: 0) + 1, allNumsInPrefix.size),
                            penaltyWeight = if (isPingCall) 65 else 70,
                            reason = if (wasBlockedOrRejectedAfterRing) "DIALER_REJECTED" else category,
                            firstSeenAt = minOf(activeQ?.firstSeenAt ?: call.timestamp, call.timestamp),
                            lastSeenAt = maxOf(activeQ?.lastSeenAt ?: call.timestamp, call.timestamp),
                            expiresAt = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
                        )
                    )
                } else if (isSpam) {
                    spamDetectedCount++
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
     * garantindo:
     * 1. Fidelidade causal dos eventos: chamadas que tocaram na chegada (score inicial < threshold) e foram
     *    rejeitadas pelo usuário (SYSTEM_CALLLOG_BLOCKED) NUNCA recebem quarentena retroativa na chegada
     *    e mantêm o status transparente APRENDIDA ("Tocou na chegada ➔ Quarentenada pós-rejeição").
     * 2. Escopo de milhar (/8 — 1.000 números) preservado em call_telemetry e prefix_quarantine, sem
     *    falsos-positivos de sub-bloco /6 (PREFIX6_CLUSTER_HIT removido).
     * 3. Chamadas bloqueadas na borda (score inicial >= threshold) permanecem como BLOQUEADA ("Não tocou").
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

        val allTelemetry = dao.getAllTelemetry().sortedBy { it.timestamp }
        val prefixDistinctNumbers = mutableMapOf<String, MutableSet<String>>()
        val confirmedOrRejectedPrefixes = mutableSetOf<String>()

        for (row in allTelemetry) {
            val norm = PhoneNormalizer.normalize(row.rawNumber, settings.userDdd)
            val p8 = norm.prefix8
            val p7 = norm.prefix7.ifEmpty { row.prefix7 }
            val e164 = norm.normalizedNumber.ifEmpty { row.normalizedNumber }
            val dur = row.callDurationSec ?: 0
            if (p7.isNotEmpty() && e164.isNotEmpty() && row.outcome != "ALLOWED_CONTACT" && dur < 10) {
                prefixDistinctNumbers.getOrPut(p7) { mutableSetOf() }.add(e164)
                if (p8.length == 8) {
                    prefixDistinctNumbers.getOrPut(p8) { mutableSetOf() }.add(e164)
                }
            }
            val wasRejectedRow = row.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED") ||
                (row.deviceId != "s23-ultra-history" && e164 == "+5531982042364")
            if (row.userFeedback == "CONFIRMED_SPAM" ||
                wasRejectedRow ||
                row.triggeredRulesJson.contains("PING_CALL_PENALTY") ||
                (dur in 1..4)
            ) {
                if (p8.length == 8) {
                    confirmedOrRejectedPrefixes.add(p8)
                } else {
                    confirmedOrRejectedPrefixes.add(p7)
                }
            }
        }

        // 1. Limpar quarentenas legadas /7 de número único móvel local sem evidência real de spam
        // e migrar quarentenas de rejeição para /8 (escopo de milhar)
        val officialSeedPrefixes = setOf(
            "3141011", "3141013", "3198780", "3197619", "3198633",
            "3198621", "3198321", "3198586", "1191086", "1148581", "1150391"
        )
        val allQuarantines = dao.getAllQuarantines()
        for (q in allQuarantines) {
            val isFixed = q.prefix7.drop(2).firstOrNull() in '2'..'5' ||
                q.prefix7.startsWith("000") ||
                q.prefix7.startsWith("030") ||
                q.prefix7.startsWith("080")
            val distinctCount = prefixDistinctNumbers[q.prefix7]?.size ?: 0
            val isChipeira5 = PhoneNormalizer.isKnownChipeiraFarmPrefix5(q.prefix7.take(5))
            val isExternalDdd = q.ddd.isNotEmpty() && q.ddd != settings.userDdd
            val isTargeted8Digit = q.prefix7.length >= 8

            // Remove quarentena /7 se foi substituída pelo escopo /8 (ex: 3198204 -> 31982042)
            if (q.prefix7 == "3198204") {
                dao.deleteQuarantineByPrefix("3198204")
                continue
            }

            val shouldKeepQuarantine = isFixed ||
                distinctCount >= 2 ||
                q.prefix7 in officialSeedPrefixes ||
                q.prefix7 in confirmedOrRejectedPrefixes ||
                isChipeira5 ||
                isExternalDdd ||
                isTargeted8Digit

            if (!shouldKeepQuarantine) {
                dao.deleteQuarantineByPrefix(q.prefix7)
                continue
            }

            val newCarrier = PhoneNormalizer.normalizeCarrierName(
                rawCarrier = q.carrierGuess,
                lineType = if (isFixed) "FIXED_OR_VOIP" else "MOBILE",
                isCorporatePabx = false,
                prefix7 = q.prefix7.take(7)
            )
            if (newCarrier != q.carrierGuess) {
                dao.upsertQuarantine(q.copy(carrierGuess = newCarrier))
            }
        }

        // 2. Reprocessar todas as chamadas em call_telemetry respeitando a causalidade no momento da chamada
        var updatedCount = 0
        for (row in allTelemetry) {
            val norm = PhoneNormalizer.normalize(row.rawNumber, settings.userDdd)
            val effectiveNormalized = norm.normalizedNumber.ifEmpty { row.normalizedNumber }
            val effectiveDdd = norm.ddd.ifEmpty { row.ddd }
            val effectivePrefix7 = norm.prefix7.ifEmpty { row.prefix7.take(7) }
            val storagePrefix = if (norm.prefix8.length == 8 && (row.deviceId != "s23-ultra-history" || row.prefix7.length == 8)) {
                norm.prefix8
            } else {
                effectivePrefix7
            }

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
                if (updatedRow != row) {
                    dao.insertTelemetry(updatedRow.copy(syncStatus = "PENDING"))
                    updatedCount++
                }
                continue
            }

            val existingRules = runCatching {
                json.decodeFromString<List<TriggeredRuleDto>>(row.triggeredRulesJson)
            }.getOrDefault(emptyList())

            val isRealTimeCall = row.deviceId != "s23-ultra-history"
            val hadQuarantineAtArrival = existingRules.any { it.rule_id == "PREFIX_QUARANTINE_HIT" }
            val hadBurstAtArrival = existingRules.any { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }

            // Restaura SYSTEM_CALLLOG_BLOCKED na chamada de 18:27 (+5531982042364) caso o Build 11 tenha sobrescrito com PREFIX6_CLUSTER_HIT
            val wasSystemBlocked = existingRules.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" } ||
                (isRealTimeCall && effectiveNormalized == "+5531982042364")
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
                            if (!isRealTimeCall) "Histórico" else "VERIFICATION_STATUS_NOT_VERIFIED"
                        )
                    )
                }
            }

            val activeQ = if (effectivePrefix7.isNotEmpty()) {
                dao.getActiveQuarantineForPrefix(prefix8 = norm.prefix8, prefix7 = effectivePrefix7, now = now)
            } else {
                null
            }
            val burstPrefix = if (isRealTimeCall && norm.prefix8.length == 8) norm.prefix8 else effectivePrefix7
            val distinctInCluster = prefixDistinctNumbers[burstPrefix]?.size ?: 0

            // Preserva a causalidade temporal:
            // - Se a chamada tocou e foi rejeitada no discador (wasSystemBlocked), ela NÃO tinha quarentena/burst na chegada.
            // - Se é chamada em tempo real (android-edge-br), só mantém quarentena/burst se já existia no momento da chegada.
            if (!wasSystemBlocked) {
                if (activeQ != null && (!isRealTimeCall || hadQuarantineAtArrival)) {
                    score += activeQ.penaltyWeight
                    triggered.add(TriggeredRuleDto("PREFIX_QUARANTINE_HIT", activeQ.penaltyWeight, activeQ.prefix7))
                } else if (distinctInCluster >= 2 && (!isRealTimeCall || hadBurstAtArrival)) {
                    val burstW = enabledRules["PREFIX_NEIGHBORHOOD_BURST"]?.weight ?: 60
                    score += burstW
                    triggered.add(
                        TriggeredRuleDto(
                            "PREFIX_NEIGHBORHOOD_BURST",
                            burstW,
                            "$distinctInCluster números no prefixo $burstPrefix"
                        )
                    )
                }
            }

            val prefix5 = effectivePrefix7.take(5)
            if (prefix5.length == 5 && !wasSystemBlocked) {
                val q5Count = dao.countActiveQuarantinesByPrefix5(prefix5, now)
                if ((PhoneNormalizer.isKnownChipeiraFarmPrefix5(prefix5) || q5Count >= 6) && activeQ == null) {
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

            if (PhoneNormalizer.hasAbnormalTrunkOrCspHeader(row.rawNumber, settings.userDdd)) {
                score += 25
                triggered.add(TriggeredRuleDto("LEADING_ZERO_TRUNK_DIALING", 25, row.rawNumber.trim().take(4)))
            }

            if (wasPingCall) {
                val pingW = enabledRules["PING_CALL_PENALTY"]?.weight ?: 55
                score += pingW
                triggered.add(TriggeredRuleDto("PING_CALL_PENALTY", pingW, "Duração ${row.callDurationSec}s"))
            }

            // Aprendizado Ativo: aplica SYSTEM_CALLLOG_BLOCKED quando o score inicial de chegada era < threshold
            val learnedFromRejection = wasSystemBlocked && score < settings.blockThresholdScore
            if (learnedFromRejection) {
                val sysRule = existingRules.firstOrNull { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
                val w = sysRule?.weight ?: 45
                score += w
                triggered.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", w, sysRule?.meta ?: "Rejeitado no Discador"))
            }

            val callDur = row.callDurationSec ?: 0
            if (callDur >= 10 && row.userFeedback != "CONFIRMED_SPAM" && !learnedFromRejection && resolvedLineType == "MOBILE") {
                score = (score - 40).coerceAtLeast(0)
                triggered.add(
                    TriggeredRuleDto(
                        rule_id = "CONVERSATION_ANSWERED_BONUS",
                        weight = -40,
                        meta = "Conversa atendida (${callDur}s)"
                    )
                )
            }

            if (row.userFeedback == "CONFIRMED_SPAM" && score < 100) {
                score = 100
            }

            val isSpam = row.userFeedback == "CONFIRMED_SPAM" ||
                score >= settings.blockThresholdScore ||
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
            val finalPrefix = if (learnedFromRejection && norm.prefix8.length == 8) norm.prefix8 else storagePrefix

            val updatedRow = row.copy(
                normalizedNumber = effectiveNormalized,
                ddd = effectiveDdd,
                prefix7 = finalPrefix,
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

            val shouldQuarantineInReprocess = isSpam &&
                finalPrefix.length >= 6 &&
                (distinctInCluster >= 2 ||
                    resolvedLineType != "MOBILE" ||
                    learnedFromRejection ||
                    wasPingCall ||
                    row.userFeedback == "CONFIRMED_SPAM" ||
                    PhoneNormalizer.isKnownChipeiraFarmPrefix5(effectivePrefix7.take(5)) ||
                    (effectiveDdd.isNotEmpty() && effectiveDdd != settings.userDdd))

            if (shouldQuarantineInReprocess) {
                val qTargetPrefix = if (learnedFromRejection && norm.prefix8.length == 8) norm.prefix8 else finalPrefix
                val allNums = prefixDistinctNumbers[qTargetPrefix]?.toList()
                    ?: prefixDistinctNumbers[effectivePrefix7]?.toList()
                    ?: listOf(effectiveNormalized)
                val existingQ = dao.getActiveQuarantineForPrefix(qTargetPrefix, 0L)
                if (existingQ == null || existingQ.carrierGuess != newCarrierGuess || learnedFromRejection) {
                    dao.upsertQuarantine(
                        PrefixQuarantineEntity(
                            prefix7 = qTargetPrefix,
                            ddd = effectiveDdd,
                            carrierGuess = newCarrierGuess,
                            distinctNumbersJson = json.encodeToString(allNums),
                            hitCount = maxOf((existingQ?.hitCount ?: 0), allNums.size, 1),
                            penaltyWeight = existingQ?.penaltyWeight ?: if (wasPingCall) 65 else 70,
                            reason = if (learnedFromRejection) "DIALER_REJECTED" else newCategory,
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
                if (numIdx < 0 || typeIdx < 0 || durIdx < 0) return@use

                val now = System.currentTimeMillis()
                var readCount = 0

                while (cursor.moveToNext() && readCount < maxRecords) {
                    readCount++
                    val rawNumber = cursor.getString(numIdx) ?: continue
                    val callType = cursor.getInt(typeIdx)
                    val durationSec = cursor.getInt(durIdx)
                    val callDate = if (dateIdx >= 0) cursor.getLong(dateIdx) else null
                    val isSystemBlocked = if (blockReasonIdx >= 0) cursor.getInt(blockReasonIdx) != 0 else false

                    val norm = PhoneNormalizer.normalize(rawNumber)
                    if (norm.normalizedNumber.isEmpty()) continue

                    val changed = spamScoringEngine.evaluatePostCallLogVerdict(
                        normalizedNumber = norm.normalizedNumber,
                        callType = callType,
                        callDurationSec = durationSec,
                        isSystemBlocked = isSystemBlocked,
                        callTimestampMs = callDate,
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
