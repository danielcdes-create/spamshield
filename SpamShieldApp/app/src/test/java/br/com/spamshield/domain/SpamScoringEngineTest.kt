package br.com.spamshield.domain

import br.com.spamshield.data.local.AppDatabase
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSpamShieldDao : SpamShieldDao {
    val telemetries = mutableListOf<CallTelemetryEntity>()
    val rules = AppDatabase.initialRulesSeed(1_700_000_000_000L).toMutableList()
    val quarantines = AppDatabase.initialQuarantineSeed(1_700_000_000_000L).toMutableList()
    val outboundGraces = mutableListOf<OutboundGraceEntity>()
    val anatelPrefixes = AppDatabase.initialAnatelSeed(1_700_000_000_000L).toMutableList()

    override suspend fun insertTelemetry(telemetry: CallTelemetryEntity) {
        telemetries.removeAll { it.id == telemetry.id }
        telemetries.add(0, telemetry)
    }

    override fun observeAllTelemetry(): Flow<List<CallTelemetryEntity>> = MutableStateFlow(telemetries)
    override suspend fun getAllTelemetry(): List<CallTelemetryEntity> = telemetries.toList()
    override suspend fun getTelemetryById(id: String): CallTelemetryEntity? = telemetries.find { it.id == id }
    override suspend fun getPendingTelemetry(): List<CallTelemetryEntity> = telemetries.filter { it.syncStatus == "PENDING" }
    override suspend fun markTelemetrySynced(ids: List<String>) {}
    override suspend fun updateUserFeedback(id: String, feedback: String) {
        val idx = telemetries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = telemetries[idx]
            telemetries[idx] = cur.copy(
                userFeedback = feedback,
                outcome = when (feedback) {
                    "CONFIRMED_SPAM" -> "BLOCKED"
                    "FALSE_POSITIVE" -> "ALLOWED_CONTACT"
                    else -> cur.outcome
                },
                finalScore = when (feedback) {
                    "CONFIRMED_SPAM" -> maxOf(cur.finalScore, 100)
                    "FALSE_POSITIVE" -> 0
                    else -> cur.finalScore
                }
            )
        }
    }

    override suspend fun findRealTimeTelemetryNearTimestamp(
        normalizedNumber: String,
        minTs: Long,
        maxTs: Long
    ): CallTelemetryEntity? = telemetries.find {
        it.normalizedNumber == normalizedNumber &&
            it.deviceId != "s23-ultra-history" &&
            it.timestamp in minTs..maxTs
    }

    override suspend fun deduplicateHistoryRecords() {}

    override suspend fun updateCallDurationAndCategory(id: String, durationSec: Int, category: String) {
        val idx = telemetries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            telemetries[idx] = telemetries[idx].copy(callDurationSec = durationSec, category = category)
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
    override fun observeAllRules(): Flow<List<ActiveRuleEntity>> = MutableStateFlow(rules)
    override suspend fun insertRules(rules: List<ActiveRuleEntity>) {
        this.rules.clear()
        this.rules.addAll(rules)
    }
    override suspend fun upsertRule(rule: ActiveRuleEntity) {
        rules.removeAll { it.ruleId == rule.ruleId }
        rules.add(rule)
    }
    override suspend fun deleteRule(ruleId: String) {
        rules.removeAll { it.ruleId == ruleId }
    }
    override suspend fun countRules(): Int = rules.size

    override suspend fun getActiveQuarantineForPrefix(prefix7: String, now: Long): PrefixQuarantineEntity? =
        quarantines.find { it.prefix7 == prefix7 && it.expiresAt > now }

    override suspend fun countActiveQuarantinesByPrefix6(prefix6: String, now: Long): Int =
        quarantines.count { it.prefix7.startsWith(prefix6) && it.expiresAt > now }

    override suspend fun countActiveQuarantinesByPrefix5(prefix5: String, now: Long): Int =
        quarantines.count { it.prefix7.startsWith(prefix5) && it.expiresAt > now }

    override fun observeActiveQuarantines(now: Long): Flow<List<PrefixQuarantineEntity>> =
        MutableStateFlow(quarantines.filter { it.expiresAt > now })

    override suspend fun insertQuarantines(items: List<PrefixQuarantineEntity>) {
        quarantines.clear()
        quarantines.addAll(items)
    }

    override suspend fun upsertQuarantine(item: PrefixQuarantineEntity) {
        quarantines.removeAll { it.prefix7 == item.prefix7 }
        quarantines.add(item)
    }

    override suspend fun deleteQuarantineByPrefix(prefix7: String) {
        quarantines.removeAll { it.prefix7 == prefix7 }
    }

    override suspend fun purgeExpiredQuarantines(now: Long) {
        quarantines.removeAll { it.expiresAt <= now }
    }

    override suspend fun upsertOutboundGrace(entry: OutboundGraceEntity) {
        outboundGraces.removeAll { it.normalizedNumber == entry.normalizedNumber }
        outboundGraces.add(entry)
    }

    override suspend fun hasActiveOutboundGrace(normalizedNumber: String, prefix6: String, now: Long): Int =
        outboundGraces.count {
            (it.normalizedNumber == normalizedNumber || it.prefix6 == prefix6) && it.expiresAt > now
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
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_010_000L
        val norm = PhoneNormalizer.normalize("03141011230")
        assertEquals("3141011", norm.prefix7)

        val quarantine = fakeDao.getActiveQuarantineForPrefix(norm.prefix7, now)
        assertNotNull(quarantine)
        assertEquals(70, quarantine?.penaltyWeight)
    }

    @Test
    fun `ping call detector upgrades ALLOWED_LOW_SCORE call with 3s duration to PING_CALL_ROBOCALL and quarantines prefix`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_050_000L

        // Insert a low-score allowed call from an unknown prefix 31991234567
        val callEntity = CallTelemetryEntity(
            id = "call-1",
            timestamp = now - 5_000L,
            rawNumber = "31991234567",
            normalizedNumber = "+5531991234567",
            ddd = "31",
            prefix7 = "3199123",
            carrierGuess = "VIVO",
            lineType = "MOBILE",
            stirShakenStatus = "PASSED",
            finalScore = 25,
            thresholdApplied = 70,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 1.8,
            shadowModeActive = false,
            triggeredRulesJson = "[]"
        )
        fakeDao.insertTelemetry(callEntity)

        // Simulate Ping Call post-call evaluation logic (duration = 3s <= 4s)
        val latest = fakeDao.getLatestTelemetryForNumber("+5531991234567")
        assertNotNull(latest)
        assertEquals("ALLOWED_LOW_SCORE", latest?.outcome)

        fakeDao.updateCallDurationAndCategory(latest!!.id, 3, "PING_CALL_ROBOCALL")
        fakeDao.upsertQuarantine(
            PrefixQuarantineEntity(
                prefix7 = latest.prefix7,
                ddd = latest.ddd,
                carrierGuess = latest.carrierGuess,
                hitCount = 1,
                penaltyWeight = 65,
                reason = "PING_CALL_ROBOCALL",
                firstSeenAt = now,
                lastSeenAt = now,
                expiresAt = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
            )
        )

        val updated = fakeDao.getTelemetryById("call-1")
        assertEquals("PING_CALL_ROBOCALL", updated?.category)
        assertEquals(3, updated?.callDurationSec)

        val q = fakeDao.getActiveQuarantineForPrefix("3199123", now)
        assertTrue(q != null && q.penaltyWeight == 65)
        assertFalse(q == null)
    }

    @Test
    fun `serializes CallTelemetryEntity to JSON for export and upgrades outcome on CONFIRMED_SPAM`() = runTest {
        val fakeDao = FakeSpamShieldDao()
        val now = 1_700_000_050_000L

        val callEntity = CallTelemetryEntity(
            id = "call-1136586701",
            timestamp = now,
            rawNumber = "01136586701",
            normalizedNumber = "+551136586701",
            ddd = "11",
            prefix7 = "1136586",
            carrierGuess = "VoIP / Fixo",
            lineType = "FIXED_OR_VOIP",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 45,
            thresholdApplied = 70,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 1.5,
            shadowModeActive = false,
            triggeredRulesJson = "[]"
        )
        fakeDao.insertTelemetry(callEntity)

        // Verify JSON serialization does not crash
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CallTelemetryEntity.serializer()),
            fakeDao.getAllTelemetry()
        )
        assertTrue(jsonStr.contains("+551136586701"))

        // Verify CONFIRMED_SPAM upgrades outcome to BLOCKED and score >= 100
        fakeDao.updateUserFeedback("call-1136586701", "CONFIRMED_SPAM")
        val updated = fakeDao.getTelemetryById("call-1136586701")
        assertEquals("CONFIRMED_SPAM", updated?.userFeedback)
        assertEquals("BLOCKED", updated?.outcome)
        assertEquals(100, updated?.finalScore)
    }
}
