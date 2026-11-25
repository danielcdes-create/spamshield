package br.com.spamshield.domain

import br.com.spamshield.domain.UserFeedbackAudit
import br.com.spamshield.data.local.AppDatabase
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.data.local.entity.RemoteQuarantinePrefixEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

open class FakeSpamShieldDao : SpamShieldDao {
    private val stateVersion = MutableStateFlow(0L)
    private fun notifyChange() {
        stateVersion.value++
    }

    private val featureEvents = MutableStateFlow<List<FeatureEventEntity>>(emptyList())
    override suspend fun insertFeatureEvents(events: List<FeatureEventEntity>) {
        featureEvents.value = (featureEvents.value + events).distinctBy { it.eventId }
    }
    override suspend fun getFeatureEvents() = featureEvents.value
    override fun observeFeatureEvents() = featureEvents
    override suspend fun getFeatureEventsByType(types: List<String>) = featureEvents.value.filter { it.eventType in types }
    override suspend fun getPendingFeatureEvents() = featureEvents.value.filter { it.syncStatus == "PENDING" }
    override suspend fun acknowledgeFeatureEvents(ids: List<String>) {
        featureEvents.value = featureEvents.value.map {
            if (it.eventId in ids) it.copy(syncStatus = "SYNCED") else it
        }
    }

    val remotePrefixes = mutableSetOf<String>()
    override suspend fun getRemoteQuarantinePrefixes() = remotePrefixes.toList()
    override suspend fun clearRemoteQuarantinePrefixes() { remotePrefixes.clear() }
    override suspend fun insertRemoteQuarantinePrefixes(items: List<RemoteQuarantinePrefixEntity>) { remotePrefixes.addAll(items.map { it.prefix7 }) }
    val syncMutations = mutableListOf<SyncMutationEntity>()
    override suspend fun enqueueSyncMutation(mutation: SyncMutationEntity) {
        syncMutations.removeAll { it.mutationId == mutation.mutationId }
        syncMutations.add(mutation)
        notifyChange()
    }
    override suspend fun getSyncMutations() = syncMutations.toList()
    override suspend fun acknowledgeSyncMutation(mutationId: String) {
        if (syncMutations.removeAll { it.mutationId == mutationId }) notifyChange()
    }
    val decisionEvents = mutableListOf<CallDecisionEventEntity>()
    override suspend fun insertDecisionEvent(event: CallDecisionEventEntity) {
        if (decisionEvents.none { it.eventId == event.eventId }) {
            decisionEvents.add(event)
            notifyChange()
        }
    }
    override suspend fun getAllDecisionEvents() = decisionEvents.sortedWith(compareBy({ it.recordedAt }, { it.eventId }))
    override fun observeDecisionEvents(): Flow<List<CallDecisionEventEntity>> =
        stateVersion.map { decisionEvents.sortedWith(compareBy({ it.recordedAt }, { it.eventId })) }
    override suspend fun getDecisionEvents(callId: String) = decisionEvents.filter { it.callId == callId }.sortedWith(compareBy({ it.recordedAt }, { it.eventId }))
    override suspend fun getDecisionEventsForCalls(callIds: List<String>) =
        decisionEvents.filter { it.callId in callIds }.sortedWith(compareBy({ it.recordedAt }, { it.eventId }))
    override suspend fun markDecisionEventsSynced(callId: String, eventIds: List<String>) {
        decisionEvents.replaceAll {
            if (it.callId == callId && it.eventId in eventIds) it.copy(syncStatus = "SYNCED") else it
        }
        notifyChange()
    }
    override suspend fun deleteDecisionEvents(eventIds: List<String>) {
        val set = eventIds.toSet()
        if (decisionEvents.removeAll { it.eventId in set }) notifyChange()
    }
    override suspend fun reassignDecisionEvents(fromCallId: String, toCallId: String) {
        decisionEvents.replaceAll {
            if (it.callId == fromCallId) it.copy(callId = toCallId, syncStatus = "PENDING") else it
        }
        notifyChange()
    }
    val telemetries = mutableListOf<CallTelemetryEntity>()
    val rules = AppDatabase.initialRulesSeed(1_700_000_000_000L).toMutableList()
    val quarantines = AppDatabase.initialQuarantineSeed(1_700_000_000_000L).toMutableList()
    val outboundGraces = mutableListOf<OutboundGraceEntity>()
    val anatelPrefixes = AppDatabase.initialAnatelSeed(1_700_000_000_000L).toMutableList()

    override suspend fun insertTelemetry(telemetry: CallTelemetryEntity) {
        telemetries.removeAll { it.id == telemetry.id }
        telemetries.add(0, telemetry)
        notifyChange()
    }
    override suspend fun deleteTelemetryById(id: String) {
        if (telemetries.removeAll { it.id == id }) notifyChange()
    }
    override suspend fun deleteUnjournaledPendingTelemetry(id: String): Int {
        val removed = telemetries.removeAll { row ->
            row.id == id && row.syncStatus == "PENDING" && row.userFeedback == "UNRATED" &&
                decisionEvents.none { it.callId == id }
        }
        if (removed) notifyChange()
        return if (removed) 1 else 0
    }

    override fun observeAllTelemetry(): Flow<List<CallTelemetryEntity>> =
        stateVersion.map { telemetries.sortedByDescending { row -> row.timestamp } }
    override suspend fun getAllTelemetry(): List<CallTelemetryEntity> =
        telemetries.sortedByDescending { it.timestamp }
    override suspend fun getTelemetryById(id: String): CallTelemetryEntity? = telemetries.find { it.id == id }
    override suspend fun getPendingTelemetry(afterTimestamp: Long, afterId: String): List<CallTelemetryEntity> =
        telemetries.filter { row ->
            (row.timestamp > afterTimestamp || row.timestamp == afterTimestamp && row.id > afterId) &&
                (row.syncStatus == "PENDING" || decisionEvents.any { it.callId == row.id && it.syncStatus == "PENDING" })
        }.sortedWith(compareBy({ it.timestamp }, { it.id })).take(100)
    override suspend fun markTelemetrySynced(ids: List<String>) {
        telemetries.replaceAll { row ->
            if (row.id in ids) row.copy(syncStatus = "SYNCED") else row
        }
        notifyChange()
    }

    override suspend fun findRealTimeTelemetryNearTimestamp(
        normalizedNumber: String,
        minTs: Long,
        maxTs: Long
    ): CallTelemetryEntity? {
        val center = minTs + (maxTs - minTs) / 2
        return telemetries.filter {
            it.normalizedNumber == normalizedNumber &&
                it.deviceId != "s23-ultra-history" &&
                it.timestamp in minTs..maxTs
        }.minWithOrNull(
            compareBy<CallTelemetryEntity> { kotlin.math.abs(it.timestamp - center) }.thenBy { it.timestamp }
        )
    }

    override suspend fun deduplicateHistoryRecords() {
        val removed = telemetries.removeAll { row ->
            row.deviceId == "s23-ultra-history" &&
                row.syncStatus == "PENDING" &&
                row.userFeedback == "UNRATED" &&
                decisionEvents.none { it.callId == row.id } &&
                telemetries.any { rt ->
                    rt.deviceId != "s23-ultra-history" &&
                        rt.normalizedNumber == row.normalizedNumber &&
                        kotlin.math.abs(rt.timestamp - row.timestamp) <= 15_000L
                }
        }
        if (removed) notifyChange()
    }

    override suspend fun updateCallDurationAndCategory(id: String, durationSec: Int, category: String) {
        val idx = telemetries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            telemetries[idx] = telemetries[idx].copy(
                callDurationSec = durationSec,
                category = category,
                syncStatus = "PENDING"
            )
            notifyChange()
        }
    }

    override suspend fun getLatestTelemetryForNumber(normalizedNumber: String): CallTelemetryEntity? =
        telemetries.filter { it.normalizedNumber == normalizedNumber }.maxByOrNull { it.timestamp }

    override suspend fun countRecentCallsFromNumber(normalizedNumber: String, sinceTimestamp: Long): Int =
        telemetries.count { it.normalizedNumber == normalizedNumber && it.timestamp >= sinceTimestamp }

    override suspend fun countDistinctNumbersInPrefix7Since(prefix7: String, sinceTimestamp: Long): Int =
        telemetries.filter { it.prefix7 == prefix7 && it.timestamp >= sinceTimestamp }
            .map { it.normalizedNumber }
            .distinct()
            .size

    override suspend fun countDistinctNumbersInPrefix6Since(prefix6: String, sinceTimestamp: Long): Int =
        telemetries.filter { it.prefix7.startsWith(prefix6) && it.timestamp >= sinceTimestamp }
            .map { it.normalizedNumber }
            .distinct()
            .size

    override suspend fun getEnabledRules(): List<ActiveRuleEntity> = rules.filter { it.enabled }.sortedBy { it.priority }
    override fun observeAllRules(): Flow<List<ActiveRuleEntity>> = stateVersion.map { rules.sortedBy { r -> r.priority } }
    override suspend fun insertRules(rules: List<ActiveRuleEntity>) {
        this.rules.removeAll { existing -> rules.any { it.ruleId == existing.ruleId } }
        this.rules.addAll(rules)
        notifyChange()
    }
    override suspend fun upsertRule(rule: ActiveRuleEntity) {
        rules.removeAll { it.ruleId == rule.ruleId }
        rules.add(rule)
        notifyChange()
    }
    override suspend fun deleteRule(ruleId: String) {
        if (rules.removeAll { it.ruleId == ruleId }) notifyChange()
    }
    override suspend fun countRules(): Int = rules.size

    open override suspend fun getActiveQuarantineForPrefix(prefix8: String, prefix7: String, now: Long): PrefixQuarantineEntity? =
        quarantines.filter { (it.prefix7 == prefix8 || it.prefix7 == prefix7) && it.expiresAt > now }
            .maxByOrNull { it.prefix7.length }

    open override suspend fun getActiveQuarantineForPrefix(prefix7: String, now: Long): PrefixQuarantineEntity? =
        quarantines.find { it.prefix7 == prefix7 && it.expiresAt > now }

    open override suspend fun countActiveQuarantinesByPrefix6(prefix6: String, now: Long): Int =
        quarantines.count { it.prefix7.startsWith(prefix6) && it.expiresAt > now }

    open override suspend fun countActiveQuarantinesByPrefix5(prefix5: String, now: Long): Int =
        quarantines.count { it.prefix7.startsWith(prefix5) && it.expiresAt > now }

    override fun observeActiveQuarantines(now: Long): Flow<List<PrefixQuarantineEntity>> =
        stateVersion.map {
            quarantines.filter { q -> q.expiresAt > now }
                .sortedWith(compareByDescending<PrefixQuarantineEntity> { it.hitCount }.thenByDescending { it.lastSeenAt })
        }

    override suspend fun getAllQuarantines(): List<PrefixQuarantineEntity> = quarantines.toList()

    override suspend fun clearAllQuarantines() {
        quarantines.clear()
        notifyChange()
    }

    override suspend fun insertQuarantines(items: List<PrefixQuarantineEntity>) {
        quarantines.clear()
        quarantines.addAll(items)
        notifyChange()
    }

    override suspend fun persistQuarantine(item: PrefixQuarantineEntity) {
        quarantines.removeAll { it.prefix7 == item.prefix7 }
        quarantines.add(item)
        notifyChange()
    }

    override suspend fun persistQuarantineDeletion(prefix7: String) {
        if (quarantines.removeAll { it.prefix7 == prefix7 }) notifyChange()
    }

    override suspend fun purgeExpiredQuarantines(now: Long) {
        if (quarantines.removeAll { it.expiresAt <= now }) notifyChange()
    }

    override suspend fun upsertOutboundGrace(entry: OutboundGraceEntity) {
        outboundGraces.removeAll { it.normalizedNumber == entry.normalizedNumber }
        outboundGraces.add(entry)
    }

    override suspend fun hasActiveOutboundGrace(normalizedNumber: String, prefix6: String, now: Long): Int =
        outboundGraces.count {
            (it.normalizedNumber == normalizedNumber || (prefix6.isNotEmpty() && it.prefix6 == prefix6)) && it.expiresAt > now
        }

    override suspend fun purgeExpiredOutboundGrace(now: Long) {
        outboundGraces.removeAll { it.expiresAt <= now }
    }

    override suspend fun getAnatelPrefix(prefix7: String): AnatelPrefixEntity? =
        anatelPrefixes.find { it.prefix7 == prefix7 }

    override suspend fun insertAnatelPrefixes(items: List<AnatelPrefixEntity>) {
        anatelPrefixes.clear()
        anatelPrefixes.addAll(items)
    }
}

class FakeContactLookup(
    private val contacts: Set<String> = emptySet(),
    private val recentOutgoingPrefixes: Set<String> = emptySet()
) : ContactAndCallLogLookup {
    override suspend fun isNumberInContacts(rawOrNormalizedNumber: String): Boolean =
        rawOrNormalizedNumber in contacts

    override suspend fun hasRecentOutgoingCall(
        normalizedNumber: String,
        prefix6: String,
        sinceTimestampMs: Long
    ): Boolean = prefix6 in recentOutgoingPrefixes
}

class SpamScoringEngineTest {

    @Test
    fun `blocks MG VoIP trunk 03141011230 with quarantine, regex and PABX range penalties`() = runTest {
        QuarantinePrefixIndex.clear()
        try {
            val fakeDao = FakeSpamShieldDao()
            val now = 1_700_000_010_000L
            QuarantinePrefixIndex.replaceActive(fakeDao.getAllQuarantines(), now)
            val engine = SpamScoringEngine(
                fakeDao,
                br.com.spamshield.data.local.SettingsDataStore(),
                FakeContactLookup()
            )

            val eval = engine.evaluateIncomingCall("03141011230", "FAILED", now)
            assertTrue(eval.shouldDisallowAndReject)
            assertEquals("BLOCKED", eval.telemetry.outcome)
            assertEquals("SPOOFING_STIR_FAILED", eval.telemetry.category)
            assertEquals("31410112", eval.telemetry.prefix7)

            val triggered = UserFeedbackAudit.decode(eval.telemetry.triggeredRulesJson)
            assertTrue(triggered.any { it.rule_id == "PREFIX7_BROAD_QUARANTINE_HIT" && it.weight == 60 })
            assertEquals(1, triggered.count { it.rule_id in HeuristicScorer.VOIP_TRUNK_GROUP })
            assertTrue(triggered.any { it.rule_id == "BLACKLIST_MG_VOIP_PABX" && it.weight == 80 })
            assertTrue(triggered.any { it.rule_id == "LEADING_ZERO_TRUNK_DIALING" && it.weight == 25 })
            assertEquals(HeuristicScorer.equation(triggered), eval.telemetry.finalScore)
        } finally {
            QuarantinePrefixIndex.clear()
        }
    }

    @Test
    fun `ping call detector upgrades ALLOWED_LOW_SCORE call with 3s duration to PING_CALL_ROBOCALL and quarantines prefix`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_050_000L

        val callEntity = CallTelemetryEntity(
            id = "call-1",
            timestamp = now - 5_000L,
            rawNumber = "31991234567",
            normalizedNumber = "+5531991234567",
            ddd = "31",
            prefix7 = "31991234",
            carrierGuess = "Vivo",
            lineType = "MOBILE",
            stirShakenStatus = "PASSED",
            finalScore = 25,
            thresholdApplied = 70,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 1.8,
            shadowModeActive = false,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]"""
        )
        fakeDao.insertTelemetry(callEntity)

        val settingsStore = br.com.spamshield.data.local.SettingsDataStore()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(fakeDao, settingsStore, lookup)

        val changed = engine.evaluatePostCallPingDetector("+5531991234567", 3, now)
        assertTrue(changed)

        val updated = fakeDao.getTelemetryById("call-1")
        assertEquals("PING_CALL_ROBOCALL", updated?.category)
        assertEquals("FLAGGED_POST_CALL_QUARANTINED", updated?.outcome)
        assertEquals(3, updated?.callDurationSec)
        assertTrue(updated!!.triggeredRulesJson.contains("PING_CALL_PENALTY"))

        val q = fakeDao.getActiveQuarantineForPrefix("31991234", "3199123", now)
        assertTrue(q != null && q.penaltyWeight == 65)
    }

    @Test
    fun `serializes telemetry export with journal and upgrades outcome via production feedback flow`() = runTest {
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
        try {
            val fakeDao = FakeSpamShieldDao()
            val now = 1_700_000_050_000L

            val callEntity = CallTelemetryEntity(
                id = "call-1136586701",
                timestamp = now,
                rawNumber = "01136586701",
                normalizedNumber = "+551136586701",
                ddd = "11",
                prefix7 = "11365867",
                carrierGuess = "Fixo",
                lineType = "FIXED_OR_VOIP",
                stirShakenStatus = "NOT_VERIFIED",
                finalScore = 45,
                thresholdApplied = 70,
                outcome = "ALLOWED_LOW_SCORE",
                category = "UNKNOWN_UNVERIFIED",
                processingLatencyMs = 1.5,
                shadowModeActive = false,
                triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":20}]"""
            )
            fakeDao.insertTelemetry(callEntity)

            val syncRepo = br.com.spamshield.sync.SyncRepository(
                fakeDao,
                br.com.spamshield.data.local.SettingsDataStore()
            )
            syncRepo.submitFeedback("call-1136586701", "CONFIRMED_SPAM", "11365867")

            val updated = fakeDao.getTelemetryById("call-1136586701")!!
            assertEquals("CONFIRMED_SPAM", updated.userFeedback)
            assertEquals("USER_BLOCKED_RETROACTIVE", updated.outcome)
            assertEquals("USER_REPORTED_SPAM", updated.category)
            assertEquals(145, updated.finalScore)
            assertTrue(updated.triggeredRulesJson.contains("USER_CONFIRMED_SPAM_OVERRIDE"))
            assertEquals(1, TelemetrySyncScheduler.enqueuedCount)
            assertEquals(setOf("FEEDBACK", "QUARANTINE_UPSERT"), fakeDao.getSyncMutations().map { it.kind }.toSet())
            assertEquals(PrefixQuarantineMatch.EXACT8, QuarantinePrefixIndex.match("11365867"))

            val exportedJson = TelemetryExport.encode(fakeDao.getAllTelemetry(), fakeDao.getAllDecisionEvents())
            val exportedRow = Json.parseToJsonElement(exportedJson).jsonArray.single().jsonObject
            assertEquals("+551136586701", exportedRow["normalizedNumber"]!!.jsonPrimitive.content)
            val eventTypes = exportedRow["decisionEvents"]!!.jsonArray.map {
                it.jsonObject["event_type"]!!.jsonPrimitive.content
            }.toSet()
            assertEquals(setOf("USER_FEEDBACK", "QUARANTINE_UPDATED"), eventTypes)
        } finally {
            QuarantinePrefixIndex.clear()
            TelemetrySyncScheduler.resetForTests()
        }
    }

    @Test
    fun `malformed custom regex rule is isolated without disabling quarantine or STIR protection`() = runTest {
        QuarantinePrefixIndex.clear()
        try {
            val fakeDao = FakeSpamShieldDao()
            val now = 1_700_000_090_000L
            QuarantinePrefixIndex.replaceAll(listOf("31995201"))
            fakeDao.upsertRule(
                ActiveRuleEntity(
                    ruleId = "BROKEN_CUSTOM_REGEX",
                    name = "Regex Malformada",
                    ruleType = "REGEX_MATCH",
                    pattern = "[unclosed-bracket",
                    weight = 90,
                    priority = 1,
                    enabled = true,
                    updatedAt = now
                )
            )
            val engine = SpamScoringEngine(
                fakeDao,
                br.com.spamshield.data.local.SettingsDataStore(),
                FakeContactLookup()
            )
            val eval = engine.evaluateIncomingCall("031995201234", "NOT_VERIFIED", now)
            assertTrue(eval.shouldDisallowAndReject)
            assertEquals("BLOCKED", eval.telemetry.outcome)
            assertTrue(eval.telemetry.triggeredRulesJson.contains("PREFIX_QUARANTINE_HIT"))
            assertFalse(eval.telemetry.triggeredRulesJson.contains("BROKEN_CUSTOM_REGEX"))
        } finally {
            QuarantinePrefixIndex.clear()
        }
    }

    @Test
    fun `toll free and special numbers never corrupt prefix7 or create bogus quarantines post-call`() = runTest {
        QuarantinePrefixIndex.clear()
        try {
            val fakeDao = FakeSpamShieldDao().apply { quarantines.clear() }
            val now = 1_700_000_120_000L
            val engine = SpamScoringEngine(
                fakeDao,
                br.com.spamshield.data.local.SettingsDataStore(),
                FakeContactLookup()
            )
            assertEquals("", engine.extractQuarantinePrefix("08007221234", ""))
            assertEquals("", engine.extractQuarantinePrefix("03031234567", ""))
            assertEquals("", engine.extractQuarantinePrefix("40041234", ""))
            assertEquals("31982042", engine.extractQuarantinePrefix("+5531982042364", "3198204"))

            val tollFreeCall = CallTelemetryEntity(
                id = "call-0800",
                timestamp = now - 3_000L,
                rawNumber = "08007221234",
                normalizedNumber = "08007221234",
                ddd = "31",
                prefix7 = "",
                carrierGuess = "VoIP",
                lineType = "TOLL_FREE_OR_SPECIAL",
                stirShakenStatus = "NOT_VERIFIED",
                finalScore = 45,
                thresholdApplied = 70,
                outcome = "ALLOWED_LOW_SCORE",
                category = "UNKNOWN_UNVERIFIED",
                processingLatencyMs = 1.0,
                shadowModeActive = false,
                triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":20}]"""
            )
            fakeDao.insertTelemetry(tollFreeCall)

            assertTrue(engine.evaluatePostCallPingDetector("08007221234", 3, now, callId = "call-0800"))
            val updated = fakeDao.getTelemetryById("call-0800")!!
            assertEquals("", updated.prefix7)
            assertEquals("FLAGGED_POST_CALL_QUARANTINED", updated.outcome)
            assertTrue(fakeDao.getAllQuarantines().isEmpty())

            engine.evaluatePostCallLogVerdict(
                normalizedNumber = "40041234",
                callType = android.provider.CallLog.Calls.REJECTED_TYPE,
                callDurationSec = 0,
                callTimestampMs = now - 1_000L,
                nowMillis = now,
                rawNumber = "40041234"
            )
            val created4004 = fakeDao.getAllTelemetry().single { it.normalizedNumber == "40041234" }
            assertEquals("", created4004.prefix7)
            assertTrue(fakeDao.getAllQuarantines().isEmpty())
            assertNull(fakeDao.getActiveQuarantineForPrefix("08007221", now))
        } finally {
            QuarantinePrefixIndex.clear()
        }
    }

    @Test
    fun `evaluatePostCallLogVerdict upgrades ALLOWED_LOW_SCORE call to BLOCKED and quarantines prefix when rejected or blocked in CallLog`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_100_000L

        val initialCall = CallTelemetryEntity(
            id = "call-031981100234",
            timestamp = now - 10_000L,
            rawNumber = "031981100234",
            normalizedNumber = "+5531981100234",
            ddd = "31",
            prefix7 = "31981100",
            carrierGuess = "TIM",
            lineType = "MOBILE",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 40,
            thresholdApplied = 70,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 1.6,
            shadowModeActive = false,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15}]"""
        )
        fakeDao.insertTelemetry(initialCall)

        val settingsStore = br.com.spamshield.data.local.SettingsDataStore()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(fakeDao, settingsStore, lookup)

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = "+5531981100234",
            callType = android.provider.CallLog.Calls.REJECTED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )
        assertTrue(changed)

        val updated = fakeDao.getTelemetryById("call-031981100234")
        assertNotNull(updated)
        assertEquals("BLOCKED", updated?.outcome)
        // Current configuration uses UNVERIFIED=20; the stored arrival fixture used 15.
        assertEquals(90, updated?.finalScore)
        assertTrue(updated!!.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED"))
        assertEquals(20, UserFeedbackAudit.decode(updated.triggeredRulesJson).single { it.rule_id == "STIR_SHAKEN_UNVERIFIED" }.weight)
        assertEquals(HeuristicScorer.equation(UserFeedbackAudit.decode(updated.triggeredRulesJson)), updated.finalScore)

        val q = fakeDao.getActiveQuarantineForPrefix(prefix8 = "31981100", prefix7 = "3198110", now = now)
        assertNotNull(q)
        assertEquals("31981100", q?.prefix7)
        assertEquals(70, q?.penaltyWeight)
    }

    @Test
    fun `recover 18h27 call 031982042364 that was corrupted by PREFIX6_CLUSTER_HIT back to APRENDIDA with SYSTEM_CALLLOG_BLOCKED`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_793_050_040_000L

        // Simulate the exact corrupted record of 031982042364 from Build 11 where PREFIX6_CLUSTER_HIT replaced SYSTEM_CALLLOG_BLOCKED
        val corrupted1827Call = CallTelemetryEntity(
            id = "f3312a4f-7c99-48ce-9b44-a36712383592",
            timestamp = 1_793_050_034_726L,
            deviceId = "android-edge-br",
            rawNumber = "031982042364",
            normalizedNumber = "+5531982042364",
            ddd = "31",
            prefix7 = "3198204",
            carrierGuess = "TIM",
            lineType = "MOBILE",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 85,
            thresholdApplied = 70,
            outcome = "BLOCKED",
            category = "ROBOCALL_PREFIX_CLUSTER",
            processingLatencyMs = 1.21698,
            shadowModeActive = false,
            callDurationSec = 0,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15,"meta":"VERIFICATION_STATUS_NOT_VERIFIED"},{"rule_id":"PREFIX6_CLUSTER_HIT","weight":45,"meta":"Sub-bloco 319820x"}]"""
        )
        fakeDao.insertTelemetry(corrupted1827Call)

        val settingsStore = br.com.spamshield.data.local.SettingsDataStore()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(fakeDao, settingsStore, lookup)

        // Reconciling with CallLog REJECTED_TYPE (5) must strip PREFIX6_CLUSTER_HIT, restore SYSTEM_CALLLOG_BLOCKED, and set 8-digit prefix 31982042
        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = "+5531982042364",
            callType = android.provider.CallLog.Calls.REJECTED_TYPE,
            callDurationSec = 0,
            callTimestampMs = 1_793_050_035_000L,
            nowMillis = now
        )
        assertTrue(changed)

        val recovered = fakeDao.getTelemetryById("f3312a4f-7c99-48ce-9b44-a36712383592")
        assertNotNull(recovered)
        assertEquals("31982042", recovered?.prefix7)
        assertEquals("BLOCKED", recovered?.outcome)
        // Recovered classification applies current UNVERIFIED=20 while preserving old events.
        assertEquals(90, recovered?.finalScore)
        assertTrue(recovered!!.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED"))
        assertEquals(20, UserFeedbackAudit.decode(recovered.triggeredRulesJson).single { it.rule_id == "STIR_SHAKEN_UNVERIFIED" }.weight)
        assertEquals(HeuristicScorer.equation(UserFeedbackAudit.decode(recovered.triggeredRulesJson)), recovered.finalScore)
        assertFalse(recovered.triggeredRulesJson.contains("PREFIX6_CLUSTER_HIT"))

        val q8 = fakeDao.getActiveQuarantineForPrefix("31982042", "3198204", now)
        assertNotNull(q8)
        assertEquals("31982042", q8?.prefix7)
        assertEquals(70, q8?.penaltyWeight)
    }

    @Test
    fun `8-digit quarantine blocks same milhar 31982042xxx but spares neighboring milhar 31982041xxx`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_100_000L
        val settingsStore = br.com.spamshield.data.local.SettingsDataStore()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(fakeDao, settingsStore, lookup)

        QuarantinePrefixIndex.clear()
        QuarantinePrefixIndex.add("31982042")
        fakeDao.upsertQuarantine(
            PrefixQuarantineEntity(
                prefix7 = "31982042",
                ddd = "31",
                carrierGuess = "TIM",
                hitCount = 1,
                penaltyWeight = 70,
                reason = "DIALER_REJECTED",
                firstSeenAt = now,
                lastSeenAt = now,
                expiresAt = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
            )
        )

        val resultSameMilhar = engine.evaluateIncomingCall(
            rawNumber = "031982042364",
            stirShakenStatus = "NOT_VERIFIED",
            nowMillis = now
        )
        assertEquals("BLOCKED", resultSameMilhar.telemetry.outcome)
        assertTrue(resultSameMilhar.shouldDisallowAndReject)
        assertTrue(resultSameMilhar.telemetry.triggeredRulesJson.contains("31982042"))

        val resultDiffMilhar = engine.evaluateIncomingCall(
            rawNumber = "031982041999",
            stirShakenStatus = "NOT_VERIFIED",
            nowMillis = now
        )
        assertEquals("ALLOWED_LOW_SCORE", resultDiffMilhar.telemetry.outcome)
        assertFalse(resultDiffMilhar.shouldDisallowAndReject)
    }

    @Test
    fun `evaluatePostCallLogVerdict does NOT alter call that was already BLOCKED at edge`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_100_000L

        val initialCall = CallTelemetryEntity(
            id = "call-03136150224",
            timestamp = now - 10_000L,
            rawNumber = "03136150224",
            normalizedNumber = "+553136150224",
            ddd = "31",
            prefix7 = "31361502",
            carrierGuess = "Fixo",
            lineType = "FIXED_OR_VOIP",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 165,
            thresholdApplied = 70,
            outcome = "BLOCKED",
            category = "ROBOCALL_PREFIX_CLUSTER",
            processingLatencyMs = 1.22,
            shadowModeActive = false,
            callDurationSec = null,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15},{"rule_id":"PREFIX_QUARANTINE_HIT","weight":65},{"rule_id":"ANATEL_PABX_VOIP_RANGE","weight":35},{"rule_id":"LEADING_ZERO_TRUNK_DIALING","weight":25}]"""
        )
        fakeDao.insertTelemetry(initialCall)

        val settingsStore = br.com.spamshield.data.local.SettingsDataStore()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(fakeDao, settingsStore, lookup)

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = "+553136150224",
            callType = android.provider.CallLog.Calls.BLOCKED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )
        assertFalse(changed)

        val tele = fakeDao.getTelemetryById("call-03136150224")
        assertNotNull(tele)
        assertEquals("BLOCKED", tele?.outcome)
        assertEquals(165, tele?.finalScore)
        assertEquals(0, tele?.callDurationSec)
        assertFalse(tele!!.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED"))
    }

    @Test
    fun `two missed calls from one ordinary number do not insert UNANSWERED_SPAM_BURST`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_200_000L
        val number = "+5511987600001"
        fakeDao.insertTelemetry(missedCall("ordinary-1", number, "11987600", "MOBILE", "TIM", now - 20_000L))
        fakeDao.insertTelemetry(missedCall("ordinary-2", number, "11987600", "MOBILE", "TIM", now - 5_000L))
        val engine = SpamScoringEngine(fakeDao, br.com.spamshield.data.local.SettingsDataStore(), FakeContactLookup())

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = number,
            callType = android.provider.CallLog.Calls.MISSED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )

        assertFalse(changed)
        assertTrue(fakeDao.getAllQuarantines().none { it.reason == "UNANSWERED_SPAM_BURST" && it.prefix7 == "11987600" })
    }

    @Test
    fun `two missed calls from one geographic landline do not insert UNANSWERED_SPAM_BURST`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_250_000L
        val number = "+553132201234"
        fakeDao.insertTelemetry(missedCall("fixo-1", number, "31322012", "FIXED_OR_VOIP", "Fixo", now - 20_000L))
        fakeDao.insertTelemetry(missedCall("fixo-2", number, "31322012", "FIXED_OR_VOIP", "Fixo", now - 5_000L))
        val engine = SpamScoringEngine(fakeDao, br.com.spamshield.data.local.SettingsDataStore(), FakeContactLookup())

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = number,
            callType = android.provider.CallLog.Calls.MISSED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )

        assertFalse(changed)
        assertTrue(fakeDao.getAllQuarantines().none { it.reason == "UNANSWERED_SPAM_BURST" && it.prefix7 == "31322012" })
    }

    @Test
    fun `two missed calls from a VoIP number still insert UNANSWERED_SPAM_BURST`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_300_000L
        val number = "+551141011111"
        fakeDao.insertTelemetry(missedCall("voip-1", number, "11410111", "FIXED_OR_VOIP", "VoIP", now - 20_000L))
        fakeDao.insertTelemetry(missedCall("voip-2", number, "11410111", "FIXED_OR_VOIP", "VoIP", now - 5_000L))
        val engine = SpamScoringEngine(fakeDao, br.com.spamshield.data.local.SettingsDataStore(), FakeContactLookup())

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = number,
            callType = android.provider.CallLog.Calls.MISSED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )

        assertTrue(changed)
        val quarantine = fakeDao.getActiveQuarantineForPrefix("11410111", "1141011", now)
        assertEquals("UNANSWERED_SPAM_BURST", quarantine?.reason)
    }

    @Test
    fun `three distinct ordinary numbers in the prefix window insert UNANSWERED_SPAM_BURST`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_400_000L
        val numbers = listOf("+5521900000011", "+5521900000022", "+5521900000033")
        numbers.forEachIndexed { index, number ->
            fakeDao.insertTelemetry(
                missedCall("distinct-$index", number, "21900000", "MOBILE", "TIM", now - (30_000L - index * 1_000L))
            )
        }
        val engine = SpamScoringEngine(fakeDao, br.com.spamshield.data.local.SettingsDataStore(), FakeContactLookup())

        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = numbers.last(),
            callType = android.provider.CallLog.Calls.MISSED_TYPE,
            callDurationSec = 0,
            nowMillis = now
        )

        assertTrue(changed)
        val quarantine = fakeDao.getActiveQuarantineForPrefix("21900000", "2190000", now)
        assertEquals("UNANSWERED_SPAM_BURST", quarantine?.reason)
    }

    private fun missedCall(
        id: String,
        normalizedNumber: String,
        prefix7: String,
        lineType: String,
        carrier: String,
        timestamp: Long
    ) = CallTelemetryEntity(
        id = id,
        timestamp = timestamp,
        rawNumber = normalizedNumber,
        normalizedNumber = normalizedNumber,
        ddd = prefix7.take(2),
        prefix7 = prefix7,
        carrierGuess = carrier,
        lineType = lineType,
        stirShakenStatus = "NOT_VERIFIED",
        finalScore = 25,
        thresholdApplied = 70,
        outcome = "ALLOWED_LOW_SCORE",
        category = "UNKNOWN_UNVERIFIED",
        processingLatencyMs = 1.0,
        shadowModeActive = false,
        triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]"""
    )
}
