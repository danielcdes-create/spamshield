package br.com.spamshield.domain

import android.provider.CallLog
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.service.ScreeningDeadline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal suspend fun SpamScoringEngine.screenIncomingCallForTest(
    rawNumber: String,
    stirShakenStatus: String,
    nowMillis: Long
): CallEvaluationResult =
    recordIncomingCall(prepareIncomingCall(rawNumber, stirShakenStatus, nowMillis), nowMillis)

class ScreeningSafetyTest {
    private val now = 1_795_000_000_000L
    private val number = "+5531991234567"
    private val prefix = "31991234"

    @Before fun resetIndex() = QuarantinePrefixIndex.clear()
    @After fun clearIndex() = QuarantinePrefixIndex.clear()

    private fun dao() = FakeSpamShieldDao().apply { quarantines.clear(); rules.clear() }
    private fun engine(dao: FakeSpamShieldDao) = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())

    @Test fun testNumberPreviewHasNoDurableOrProtectionEffects() = runTest {
        val dao = dao()
        val result = engine(dao).evaluateIncomingCall(number, "FAILED", now)
        assertTrue(result.shouldDisallowAndReject)
        assertTrue(dao.getAllTelemetry().isEmpty())
        assertTrue(dao.getAllDecisionEvents().isEmpty())
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertTrue(dao.getSyncMutations().isEmpty())
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match(prefix, now))
    }

    @Test fun blockedEvaluationDoesNotLearnUntilAfterSubmission() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val prepared = engine.prepareIncomingCall(number, "FAILED", now)
        assertTrue(prepared.result.shouldDisallowAndReject)
        assertTrue(dao.getAllTelemetry().isEmpty())
        assertTrue(dao.getAllQuarantines().isEmpty())

        val result = engine.recordIncomingCall(prepared, now + 20, DecisionDetails(disallowCall = true))
        val quarantine = dao.getAllQuarantines().single()
        assertEquals(now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS, quarantine.expiresAt)
        assertEquals(now, dao.getSyncMutations().single().createdAt)
        assertEquals("QUARANTINE_AUTO_UPSERT", dao.getSyncMutations().single().kind)
        assertEquals(PrefixQuarantineMatch.EXACT8, QuarantinePrefixIndex.match(prefix, now + 20))
        assertEquals(setOf("SCREENING_DECISION", "SCREENING_RESPONSE_SUBMITTED", "QUARANTINE_UPDATED"),
            dao.getDecisionEvents(result.telemetry.id).map { it.eventType }.toSet())
    }

    @Test fun failedSubmissionAndShadowVerdictsDoNotLearn() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val prepared = engine.prepareIncomingCall(number, "FAILED", now)
        engine.recordIncomingCall(prepared, now + 20, DecisionDetails(disallowCall = true), "BinderException")
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertTrue(dao.getAllDecisionEvents().any { it.eventType == "SCREENING_RESPONSE_FAILED" })

        val shadowRow = prepared.result.telemetry.copy(
            id = "shadow", timestamp = now + 300, outcome = "SHADOW_BLOCKED", shadowModeActive = true
        )
        val shadow = PreparedScreening(
            prepared.result.copy(telemetry = shadowRow, shouldDisallowAndReject = false)
        ) { DecisionTrace.snapshot(shadowRow) }
        engine.recordIncomingCall(shadow, now + 350, DecisionDetails(disallowCall = false))
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertTrue(dao.getSyncMutations().isEmpty())
    }

    @Test fun liveBlockKeepsExistingManualQuarantineIntact() = runTest {
        val dao = dao()
        val manual = PrefixQuarantineEntity(
            prefix7 = prefix, ddd = "31", carrierGuess = "Manual", hitCount = 8, penaltyWeight = 85,
            firstSeenAt = now - 200, lastSeenAt = now - 100, expiresAt = now + 30L * 86_400_000, reason = "MANUAL"
        )
        dao.persistQuarantine(manual)
        val engine = engine(dao)
        val prepared = engine.prepareIncomingCall(number, "NOT_VERIFIED", now)
        assertTrue(prepared.result.shouldDisallowAndReject)
        engine.recordIncomingCall(prepared, now + 20, DecisionDetails(disallowCall = true))
        assertEquals(manual, dao.getAllQuarantines().single())
        assertTrue(dao.getSyncMutations().isEmpty())
    }

    @Test fun aNewerDeletionPreventsReplayingOldEvidenceLocally() = runTest {
        val dao = dao()
        dao.enqueueSyncMutation(SyncMutationEntity("newer-delete", "QUARANTINE_DELETE", prefix, "", now))
        val engine = engine(dao)
        val prepared = engine.prepareIncomingCall(number, "FAILED", now - 86_400_000)
        engine.recordIncomingCall(prepared, now + 20, DecisionDetails(disallowCall = true))
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertEquals("QUARANTINE_DELETE", dao.getSyncMutations().single().kind)
    }

    @Test fun snapshotRevertsARejectedAutomaticQuarantineAndKeepsNewerManualEdit() = runTest {
        val dao = dao()
        val now = System.currentTimeMillis()
        val auto = PrefixQuarantineEntity(
            prefix7 = prefix, ddd = "31", carrierGuess = "Outros", hitCount = 1, penaltyWeight = 70,
            firstSeenAt = now - 100, lastSeenAt = now - 100, expiresAt = now + 86_400_000, reason = "LEARNED"
        )
        assertTrue(dao.installAutomaticQuarantine(auto, now))
        var rejected = dao.getSyncMutations().single()
        val remoteBroad = auto.copy(prefix7 = prefix.take(7), lastSeenAt = now, reason = "MANUAL")
        dao.applyRemoteQuarantines(listOf(remoteBroad), rejectedAutomatic = listOf(rejected))
        assertEquals(listOf(remoteBroad), dao.getAllQuarantines())
        assertTrue(dao.getSyncMutations().isEmpty())

        dao.persistQuarantineDeletion(remoteBroad.prefix7)
        assertTrue(dao.installAutomaticQuarantine(auto, now))
        rejected = dao.getSyncMutations().single()
        val localManual = auto.copy(lastSeenAt = now + 100, expiresAt = now + 30L * 86_400_000, reason = "MANUAL")
        dao.upsertQuarantine(localManual)
        dao.applyRemoteQuarantines(listOf(remoteBroad), rejectedAutomatic = listOf(rejected))
        assertEquals(setOf(remoteBroad, localManual), dao.getAllQuarantines().toSet())
        assertEquals("QUARANTINE_UPSERT", dao.getSyncMutations().single().kind)
    }

    @Test fun blockByAnotherAppNeverAddsTheManualRejectionRule() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val screened = engine.screenIncomingCallForTest(number, "PASSED", now)
        assertFalse(engine.evaluatePostCallLogVerdict(
            number, CallLog.Calls.BLOCKED_TYPE, 0, callTimestampMs = now, nowMillis = now + 10,
            systemBlockReason = CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE,
            systemScreeningComponent = "example.other/.ScreeningService", systemScreeningAppIsSpamShield = false
        ))
        assertEquals(screened.telemetry.finalScore, dao.getTelemetryById(screened.telemetry.id)?.finalScore)
        assertTrue(dao.getAllQuarantines().isEmpty())
        assertTrue(dao.getAllDecisionEvents().any { it.eventType == "CALL_LOG_OBSERVATION" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun earlyCallLogWaitsForTheClosestPendingScreeningAudit() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val prior = engine.screenIncomingCallForTest(number, "PASSED", now - 9_000)
        val prepared = engine.prepareIncomingCall(number, "PASSED", now)
        val pending = engine.beginScreeningAudit(prepared.result.telemetry.normalizedNumber, now)
        val observed = async {
            engine.evaluatePostCallLogVerdict(number, CallLog.Calls.MISSED_TYPE, 0,
                callTimestampMs = now + 811, nowMillis = now + 1_000)
        }
        runCurrent()
        assertFalse(observed.isCompleted)
        assertTrue(dao.getDecisionEvents(prior.telemetry.id).none { it.eventType == "CALL_LOG_OBSERVATION" })

        val screened = engine.recordIncomingCall(prepared, now + 100, DecisionDetails(disallowCall = false))
        engine.endScreeningAudit(pending)
        observed.await()
        assertEquals(2, dao.getAllTelemetry().size)
        val observation = dao.getDecisionEvents(screened.telemetry.id).single { it.eventType == "CALL_LOG_OBSERVATION" }
        assertEquals(now + 811, DecisionTrace.details(observation).observedCallTimestamp)
    }

    @Test fun earlyCallLogCanBeRetriedAfterItsWaitExpires() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val prepared = engine.prepareIncomingCall(number, "FAILED", now)
        val pending = engine.beginScreeningAudit(prepared.result.telemetry.normalizedNumber, now)
        suspend fun observeCallLog() = engine.evaluatePostCallLogVerdict(number, CallLog.Calls.BLOCKED_TYPE, 0,
            callTimestampMs = now + 811, nowMillis = now + 2_000)
        assertFalse(observeCallLog())
        assertTrue(dao.getAllTelemetry().isEmpty())

        val screened = engine.recordIncomingCall(prepared, now + 100, DecisionDetails(disallowCall = true))
        assertTrue(engine.endScreeningAudit(pending))
        observeCallLog()
        assertEquals(1, dao.getAllTelemetry().size)
        assertEquals(1, dao.getDecisionEvents(screened.telemetry.id).count { it.eventType == "CALL_LOG_OBSERVATION" })
    }

    @Test fun decisionCompletedAtDeadlineCannotSendTheNormalResponse() {
        val start = 500_000_000L
        val deadline = ScreeningDeadline(start, { start + ScreeningDeadline.BUDGET_MS * 1_000_000L })
        assertTrue(deadline.claim()!!.timedOut)
        assertNull(deadline.claim())
    }

    @Test fun deadlineAndEvaluationRaceHaveExactlyOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val deadline = ScreeningDeadline(0L, { 1_000_000L })
            val bothReady = CountDownLatch(2)
            val begin = CountDownLatch(1)
            val contenders = List(2) {
                pool.submit<ScreeningDeadline.Claim?> {
                    bothReady.countDown()
                    check(begin.await(5, TimeUnit.SECONDS))
                    deadline.claim()
                }
            }
            assertTrue(bothReady.await(5, TimeUnit.SECONDS))
            begin.countDown()
            val winner = contenders.mapNotNull { it.get(5, TimeUnit.SECONDS) }.single()
            assertFalse(winner.timedOut)
        } finally {
            pool.shutdownNow()
        }
    }
}
