package br.com.spamshield.domain

import android.provider.CallLog
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.service.historyQuarantineExpiration
import br.com.spamshield.service.reprocessStoredTelemetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryCausalityTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val number = "+5531991230001"

    @Test fun historyProtectionExpiresRelativeToEvidenceTime() {
        assertEquals(now + 6 * day, historyQuarantineExpiration(now - day, now))
        assertNull(historyQuarantineExpiration(now - 7 * day, now))
        assertNull(historyQuarantineExpiration(now - 8 * day, now))
        assertNull(historyQuarantineExpiration(now + 1, now))
    }

    @Test fun reprocessUsesRecordedQuarantineEvenAfterRemoval() = runTest {
        val dao = emptyDao()
        val call = row("original-match", now - 10 * day)
        dao.insertTelemetry(call)
        DecisionTrace.record(dao, call.id, "SCREENING_DECISION", "CALL_SCREENING_SERVICE",
            DecisionTrace.snapshot(call).copy(quarantineMatch = "EXACT8", distinctNumbers24h = 2),
            at = call.timestamp)
        val original = dao.getDecisionEvents(call.id).single()

        reprocessStoredTelemetry(dao, SettingsDataStore())

        val current = dao.getTelemetryById(call.id)!!
        val snapshot = reprocessed(dao, call)
        assertEquals("EXACT8", snapshot.quarantineMatch)
        assertEquals(2, snapshot.distinctNumbers24h)
        assertEquals(70, current.thresholdApplied)
        assertEquals(current.thresholdApplied, snapshot.threshold)
        assertTrue(snapshot.rules.any { it.rule_id == "PREFIX_QUARANTINE_HIT" })
        assertTrue(snapshot.rules.any { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" })
        assertEquals(original, dao.getDecisionEvents(call.id).single { it.eventType == "SCREENING_DECISION" })
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertTrue(dao.getSyncMutations().isEmpty())
    }

    @Test fun reprocessDoesNotLearnFromFutureQuarantineOrAndroidBlockType() = runTest {
        val dao = emptyDao()
        val call = row("original-no-match", now - 2 * day)
        dao.insertTelemetry(call)
        val manual = PrefixQuarantineEntity(call.prefix7, "31", null, hitCount = 1, penaltyWeight = 70,
            reason = "MANUAL", firstSeenAt = now, lastSeenAt = now, expiresAt = now + 30 * day)
        dao.persistQuarantine(manual)
        DecisionTrace.record(dao, call.id, "SCREENING_DECISION", "CALL_SCREENING_SERVICE",
            DecisionTrace.snapshot(call).copy(quarantineMatch = "NONE", distinctNumbers24h = 1),
            at = call.timestamp)
        DecisionTrace.record(dao, call.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = CallLog.Calls.BLOCKED_TYPE, systemBlockReason = 1),
            at = call.timestamp + 1)

        reprocessStoredTelemetry(dao, SettingsDataStore())

        val snapshot = reprocessed(dao, call)
        assertEquals("NONE", snapshot.quarantineMatch)
        assertFalse(snapshot.rules.any { it.rule_id == "PREFIX_QUARANTINE_HIT" })
        assertFalse(snapshot.rules.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" })
        assertEquals(listOf(manual), dao.getAllQuarantines())
        assertTrue(dao.getSyncMutations().isEmpty())
    }

    @Test fun reprocessKeepsRecordedBurstWhenUserLaterRejectsTheCall() = runTest {
        val dao = emptyDao()
        val burst = dao.rules.single { it.ruleId == "PREFIX_NEIGHBORHOOD_BURST" }
        dao.upsertRule(burst.copy(weight = 5))
        val call = row("rejected-after-burst", now)
        dao.insertTelemetry(call)
        DecisionTrace.record(dao, call.id, "SCREENING_DECISION", "CALL_SCREENING_SERVICE",
            DecisionTrace.snapshot(call).copy(quarantineMatch = "NONE", distinctNumbers24h = 2),
            at = call.timestamp)
        DecisionTrace.record(dao, call.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = CallLog.Calls.REJECTED_TYPE),
            at = call.timestamp + 1)

        reprocessStoredTelemetry(dao, SettingsDataStore())

        val rules = reprocessed(dao, call).rules
        assertEquals(5, rules.single { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }.weight)
        assertTrue(rules.any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" })
        assertTrue(dao.getAllQuarantines().isEmpty())
    }

    @Test fun historicalBurstCountsDistinctNumbersInThePrecedingDay() = runTest {
        val dao = emptyDao()
        val old = row("old", now - 3 * day, "+5531991230002")
        val recent = row("recent", now - 10_000)
        val repeated = row("repeat", now - 5_000)
        val neighbor = row("neighbor", now, "+5531991230003")
        listOf(old, recent, repeated, neighbor).forEach {
            dao.insertTelemetry(it.copy(deviceId = "s23-ultra-history"))
        }

        reprocessStoredTelemetry(dao, SettingsDataStore())

        assertEquals(1, reprocessed(dao, recent).distinctNumbers24h)
        assertEquals(1, reprocessed(dao, repeated).distinctNumbers24h)
        val snapshot = reprocessed(dao, neighbor)
        assertEquals(2, snapshot.distinctNumbers24h)
        assertEquals("distinct_24h=2", snapshot.rules.single { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }.meta)
        assertTrue(dao.getAllQuarantines().isEmpty())
    }

    @Test fun reprocessRetainsTheCallIdOfPreviouslyJournaledRows() = runTest {
        val dao = emptyDao()
        val first = row("calllog-legacy", now)
        val second = row("screening", now)
        dao.insertTelemetry(first)
        dao.insertTelemetry(second)
        DecisionTrace.record(dao, first.id, "HISTORY_ASSESSMENT", "HISTORY_IMPORT",
            DecisionTrace.snapshot(first), at = first.timestamp)
        DecisionTrace.record(dao, second.id, "SCREENING_DECISION", "CALL_SCREENING_SERVICE",
            DecisionTrace.snapshot(second), at = second.timestamp)
        val events = dao.getAllDecisionEvents()

        reprocessStoredTelemetry(dao, SettingsDataStore())

        assertEquals(setOf(first.id, second.id), dao.getAllTelemetry().map { it.id }.toSet())
        assertTrue(dao.getAllDecisionEvents().containsAll(events))
    }

    private fun emptyDao() = FakeSpamShieldDao().apply {
        quarantines.clear()
        anatelPrefixes.clear()
    }

    private suspend fun reprocessed(dao: FakeSpamShieldDao, call: CallTelemetryEntity) =
        DecisionTrace.details(dao.getDecisionEvents(call.id).single { it.eventType == "REPROCESS_EVALUATION" })

    private fun row(id: String, at: Long, raw: String = number): CallTelemetryEntity {
        val normalized = PhoneNormalizer.normalize(raw)
        return CallTelemetryEntity(
            id = id,
            timestamp = at,
            rawNumber = raw,
            normalizedNumber = normalized.normalizedNumber,
            ddd = normalized.ddd,
            prefix7 = normalized.storedPrefix7.orEmpty(),
            carrierGuess = normalized.carrierHint,
            lineType = normalized.lineType,
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 0,
            thresholdApplied = 90,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 0.0,
            shadowModeActive = false,
            triggeredRulesJson = "[]"
        )
    }
}
