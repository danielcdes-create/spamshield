package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.service.reprocessStoredTelemetry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EngineCorrectionsTest {
    private val now = 1_800_000_000_000L

    @Before fun resetIndex() = QuarantinePrefixIndex.clear()
    @After fun clearIndex() = QuarantinePrefixIndex.clear()

    private fun dao() = FakeSpamShieldDao().apply {
        quarantines.clear()
        anatelPrefixes.clear()
    }

    private fun engine(dao: FakeSpamShieldDao) = SpamScoringEngine(
        dao, SettingsDataStore(), FakeContactLookup()
    )

    private fun quarantine(expires: Long) = PrefixQuarantineEntity(
        prefix7 = "31991234", ddd = "31", carrierGuess = "Vivo",
        distinctNumbersJson = "[]", hitCount = 1, penaltyWeight = 70,
        reason = "TEST_FIXTURE", firstSeenAt = now - 100_000,
        lastSeenAt = now - 100_000, expiresAt = expires
    )

    @Test fun utilityOutgoingGraceNeverAllowsAnUnrelated0303() = runTest {
        val outgoing = PhoneNormalizer.normalize("10315")
        val incoming = PhoneNormalizer.normalize("03031234567")
        assertEquals("", outgoing.prefix6)
        assertEquals("", incoming.prefix6)
        assertNotEquals(outgoing.normalizedNumber, incoming.normalizedNumber)
        val dao = dao()
        dao.upsertOutboundGrace(OutboundGraceEntity(outgoing.normalizedNumber, "",
            now + SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS))
        val result = engine(dao).evaluateIncomingCall("03031234567", "PASSED", nowMillis = now)
        assertEquals("BLOCKED", result.telemetry.outcome)
        assertTrue(result.shouldDisallowAndReject)
    }

    @Test fun eightSecondPassedCallDoesNotQuarantineWhenScoreRemainsBelowThreshold() = runTest {
        val dao = dao()
        val scoring = engine(dao)
        val first = scoring.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now)
        assertEquals(25, first.telemetry.finalScore)
        assertTrue(scoring.evaluatePostCallPingDetector(first.telemetry.normalizedNumber, 8,
            nowMillis = now + 10_000, callId = first.telemetry.id))
        val after = dao.getTelemetryById(first.telemetry.id)!!
        assertEquals(55, after.finalScore)
        assertEquals("ALLOWED_LOW_SCORE", after.outcome)
        assertNull(dao.getActiveQuarantineForPrefix("31991234", now + 10_000))
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31991234", now + 10_000))
        val next = scoring.evaluateIncomingCall("+5531991234567", "PASSED",
            nowMillis = now + SpamScoringEngine.EMERGENCY_REPEAT_WINDOW_MS + 1_000)
        assertEquals("ALLOWED_LOW_SCORE", next.telemetry.outcome)
        assertFalse(next.shouldDisallowAndReject)
    }

    @Test fun quarantineExpiresWithoutAnyRefreshOrSync() = runTest {
        val dao = dao()
        dao.upsertQuarantine(quarantine(now + 10))
        QuarantinePrefixIndex.replaceActive(dao.getAllQuarantines(), now)
        assertEquals(PrefixQuarantineMatch.EXACT8, QuarantinePrefixIndex.match("31991234", now))
        assertEquals(1, QuarantinePrefixIndex.countWithPrefix("3199", now))
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31991234", now + 10))
        assertEquals(0, QuarantinePrefixIndex.countWithPrefix("3199", now + 10))
        val result = engine(dao).evaluateIncomingCall("+5531991234567", "PASSED", nowMillis = now + 10)
        assertEquals(25, result.telemetry.finalScore)
        assertFalse(result.shouldDisallowAndReject)
    }

    @Test fun databaseSnapshotNeverRevivesAnExpiredQuarantine() = runTest {
        val dao = dao()
        dao.upsertQuarantine(quarantine(now - 1))
        QuarantinePrefixIndex.replaceActive(dao.getAllQuarantines(), now)
        assertEquals(0, QuarantinePrefixIndex.activeSize(now))
        val result = engine(dao).evaluateIncomingCall("+5531991234567", "PASSED", nowMillis = now)
        assertEquals(25, result.telemetry.finalScore)
        assertFalse(result.shouldDisallowAndReject)
    }

    @Test fun firstScreeningLoadsTheDatabaseBeforeUsingAColdCache() = runTest {
        val dao = dao()
        dao.upsertQuarantine(quarantine(now + 10_000))
        assertFalse(QuarantinePrefixIndex.isReady)
        val result = engine(dao).evaluateIncomingCall("+5531991234567", "PASSED", nowMillis = now)
        assertTrue(QuarantinePrefixIndex.isReady)
        assertTrue(result.shouldDisallowAndReject)
        assertTrue(result.telemetry.triggeredRulesJson.contains("PREFIX_QUARANTINE_HIT"))
    }

    @Test fun refreshRetriesInsteadOfOverwritingAConcurrentCallUpdate() = runTest {
        val dao = dao()
        QuarantinePrefixIndex.replaceActive(emptyList(), now)
        var reads = 0
        QuarantinePrefixIndex.refreshActive(now) {
            val snapshot = dao.getAllQuarantines()
            if (++reads == 1) {
                val newEntry = quarantine(now + 10_000)
                dao.upsertQuarantine(newEntry)
                QuarantinePrefixIndex.add(newEntry.prefix7, newEntry.expiresAt)
            }
            snapshot
        }
        assertEquals(2, reads)
        assertEquals(PrefixQuarantineMatch.EXACT8, QuarantinePrefixIndex.match("31991234", now))
    }

    @Test fun allowedShortCircuitsAlwaysObeyTheNonNegativeScoreEquation() = runTest {
        val repeatDao = dao()
        val repeatEngine = engine(repeatDao)
        repeatEngine.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now)
        val repeat = repeatEngine.evaluateIncomingCall("+5531991234567", "PASSED", nowMillis = now + 1_000)
        assertEquals("ALLOWED_EMERGENCY_REPEAT", repeat.telemetry.outcome)
        assertEquals(0, repeat.telemetry.finalScore)
        assertEquals(HeuristicScorer.equation(UserFeedbackAudit.decode(repeat.telemetry.triggeredRulesJson)), repeat.telemetry.finalScore)

        val graceDao = dao()
        graceDao.upsertOutboundGrace(OutboundGraceEntity("+5531991234567", "319912", now + 10_000))
        val grace = engine(graceDao).evaluateIncomingCall("+5531991234567", "PASSED", nowMillis = now)
        assertEquals("ALLOWED_OUTBOUND_GRACE", grace.telemetry.outcome)
        assertEquals(0, grace.telemetry.finalScore)
    }

    @Test fun outgoingCallLogNeverCreatesIncomingSpamTelemetry() = runTest {
        val dao = dao()
        val changed = engine(dao).evaluatePostCallLogVerdict("+5531991234567", 2, 8,
            callTimestampMs = now, nowMillis = now + 10_000)
        assertFalse(changed)
        assertTrue(dao.getAllTelemetry().isEmpty())
        assertTrue(dao.getAllDecisionEvents().isEmpty())
        assertTrue(dao.getAllQuarantines().isEmpty())
    }

    @Test fun legacyOutgoingCorrectionPreservesTheObservedEvent() = runTest {
        val dao = dao()
        val row = engine(dao).screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now).telemetry
        dao.deleteDecisionEvents(dao.getDecisionEvents(row.id).map { it.eventId })
        DecisionTrace.record(dao, row.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = 2, observedCallTimestamp = now), at = now + 10_000)
        val original = dao.getDecisionEvents(row.id).single()
        reprocessStoredTelemetry(dao, SettingsDataStore())
        val corrected = dao.getTelemetryById(row.id)!!
        assertEquals("OUTGOING_CALL", corrected.outcome)
        assertEquals(0, corrected.finalScore)
        assertEquals(original, dao.getDecisionEvents(row.id).single { it.eventType == "CALL_LOG_OBSERVATION" })
        assertTrue(dao.getAllQuarantines().isEmpty())
    }

    @Test fun e164VoipDoesNotReceiveAZeroHeaderPenalty() {
        val result = HeuristicScorer.score(ScoreInput(
            rawNumber = "+553121001234", normalizedNumber = "+553121001234", ddd = "31",
            prefix7 = "31210012", subscriberNumber = "21001234", carrierGuess = "VoIP",
            lineType = "FIXED_OR_VOIP", stirShakenStatus = "PASSED"
        ))
        assertEquals(60, result.finalScore)
        assertEquals("ALLOWED_LOW_SCORE", result.outcome)
        assertTrue(result.triggeredRules.none { it.rule_id == "LEADING_ZERO_TRUNK_DIALING" })
    }

    @Test fun disabledVoipBlacklistsAreNotReintroducedByHardcodedFallbacks() = runTest {
        val dao = dao()
        val regex = dao.rules.single { it.ruleId == "REGEX_MG_VOIP_TRUNKS" }
        dao.upsertRule(regex.copy(enabled = false))
        dao.upsertRule(regex.copy(ruleId = "BLACKLIST_MG_VOIP_PABX", enabled = false))
        val result = engine(dao).evaluateIncomingCall("+553141011234", "PASSED", nowMillis = now)
        assertEquals(60, result.telemetry.finalScore)
        assertFalse(result.shouldDisallowAndReject)
        val rules = UserFeedbackAudit.decode(result.telemetry.triggeredRulesJson)
        assertTrue(rules.none { it.rule_id == "REGEX_MG_VOIP_TRUNKS" || it.rule_id == "BLACKLIST_MG_VOIP_PABX" })
        assertEquals(35, rules.single { it.rule_id == "ANATEL_PABX_VOIP_RANGE" }.weight)
    }

    @Test fun configuredHeuristicWeightAppliesToLiveScreeningAndReprocessing() = runTest {
        val dao = dao()
        val unknown = dao.rules.single { it.ruleId == "NOT_IN_CONTACTS" }
        dao.upsertRule(unknown.copy(weight = 7))
        val result = engine(dao).screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now)
        assertEquals(7, result.telemetry.finalScore)
        val original = dao.getDecisionEvents(result.telemetry.id).single { it.eventType == "SCREENING_DECISION" }
        dao.upsertRule(unknown.copy(weight = 12))
        reprocessStoredTelemetry(dao, SettingsDataStore())
        assertEquals(12, dao.getTelemetryById(result.telemetry.id)!!.finalScore)
        assertEquals(original, dao.getDecisionEvents(result.telemetry.id).single { it.eventType == "SCREENING_DECISION" })
    }

    @Test fun disabledUnverifiedRuleDoesNotReturnAsItsDefaultWeight() = runTest {
        val dao = dao()
        dao.upsertRule(dao.rules.single { it.ruleId == "STIR_SHAKEN_UNVERIFIED" }.copy(enabled = false))
        val result = engine(dao).evaluateIncomingCall("+5531991234567", "NOT_VERIFIED", nowMillis = now)
        assertEquals(25, result.telemetry.finalScore)
        assertFalse(result.telemetry.triggeredRulesJson.contains("STIR_SHAKEN_UNVERIFIED"))
    }

    @Test fun customVoipRegexIsNotReplacedWithTheBuiltInPatternWhenItDoesNotMatch() = runTest {
        val dao = dao()
        val original = dao.rules.single { it.ruleId == "REGEX_MG_VOIP_TRUNKS" }
        dao.upsertRule(original.copy(pattern = "^999$"))
        dao.upsertRule(original.copy(ruleId = "BLACKLIST_MG_VOIP_PABX", pattern = "^999$"))
        val result = engine(dao).evaluateIncomingCall("+553141011234", "PASSED", nowMillis = now)
        assertEquals(60, result.telemetry.finalScore)
        assertFalse(result.shouldDisallowAndReject)
    }

    @Test fun disabledPingRuleCannotQuarantineDuringPostCallEvaluation() = runTest {
        val dao = dao()
        dao.upsertRule(dao.rules.single { it.ruleId == "PING_CALL_PENALTY" }.copy(enabled = false))
        val scoring = engine(dao)
        val first = scoring.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now)
        scoring.evaluatePostCallPingDetector(first.telemetry.normalizedNumber, 3,
            nowMillis = now + 10_000, callId = first.telemetry.id)
        val after = dao.getTelemetryById(first.telemetry.id)!!
        assertEquals(25, after.finalScore)
        assertFalse(after.triggeredRulesJson.contains("PING_CALL_PENALTY"))
        assertTrue(dao.getAllQuarantines().isEmpty())
    }

    @Test fun disabledContactRuleCannotRemoveTheContactAllowProtection() = runTest {
        val dao = dao()
        dao.upsertRule(dao.rules.single { it.ruleId == "CONTACT_WHITELIST" }.copy(enabled = false))
        val contactEngine = SpamScoringEngine(dao, SettingsDataStore(),
            FakeContactLookup(contacts = setOf("+5531991234567")))
        val result = contactEngine.evaluateIncomingCall("+5531991234567", "FAILED", nowMillis = now)
        assertEquals("ALLOWED_CONTACT", result.telemetry.outcome)
        assertEquals(0, result.telemetry.finalScore)
        assertFalse(result.shouldDisallowAndReject)
    }

    @Test fun confirmingSpamAfterFalsePositiveRemovesTheObsoleteAllowOverride() = runTest {
        val dao = dao()
        val original = engine(dao).screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now).telemetry
        val falsePositive = original.copy(outcome = "ALLOWED_CONTACT", category = "LEGITIMATE",
            finalScore = 0, userFeedback = "FALSE_POSITIVE", triggeredRulesJson = UserFeedbackAudit.encode(
                UserFeedbackAudit.decode(original.triggeredRulesJson) +
                    br.com.spamshield.data.local.entity.TriggeredRuleDto("USER_FALSE_POSITIVE_OVERRIDE", -1000)
            ))
        TelemetrySyncScheduler.resetForTests()
        val corrected = UserFeedbackAudit.applyConfirmedSpam(falsePositive)
        assertEquals("USER_BLOCKED_RETROACTIVE", corrected.outcome)
        assertEquals(125, corrected.finalScore)
        assertEquals(0, TelemetrySyncScheduler.enqueuedCount)
        assertFalse(corrected.triggeredRulesJson.contains("USER_FALSE_POSITIVE_OVERRIDE"))
    }

    @Test fun expiredCallLogReplayUpdatesClassificationWithoutCreatingFreshQuarantine() = runTest {
        for ((callType, duration) in listOf(5 to 0, 1 to 3)) {
            QuarantinePrefixIndex.clear()
            val dao = dao()
            val scoring = engine(dao)
            val arrival = scoring.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now).telemetry
            val original = dao.getDecisionEvents(arrival.id).single { it.eventType == "SCREENING_DECISION" }
            val observedAt = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS + 86_400_000
            scoring.evaluatePostCallLogVerdict(arrival.normalizedNumber, callType, duration,
                callTimestampMs = now, nowMillis = observedAt)
            val after = dao.getTelemetryById(arrival.id)!!
            assertTrue(after.finalScore >= after.thresholdApplied)
            assertTrue(dao.getAllQuarantines().isEmpty())
            assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31991234", observedAt))
            assertEquals(original, dao.getDecisionEvents(arrival.id).single { it.eventType == "SCREENING_DECISION" })
            val post = DecisionTrace.details(dao.getDecisionEvents(arrival.id).single { it.eventType == "POST_CALL_EVALUATION" })
            assertNotNull(post.ruleSetFingerprint)
            assertTrue(post.evaluatedRules.isNotEmpty())
        }
    }

    @Test fun repeatedReconciliationDoesNotExtendQuarantineBeyondTheCallEvidenceDate() = runTest {
        val dao = dao()
        val scoring = engine(dao)
        val arrival = scoring.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now).telemetry
        scoring.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0,
            callTimestampMs = now, nowMillis = now + 1_000)
        val originalExpiry = now + SpamScoringEngine.QUARANTINE_DEFAULT_TTL_MS
        assertEquals(originalExpiry, dao.getAllQuarantines().single().expiresAt)
        scoring.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0,
            callTimestampMs = now, nowMillis = now + 86_400_000)
        val quarantine = dao.getAllQuarantines().single()
        assertEquals(originalExpiry, quarantine.expiresAt)
        assertEquals(now, quarantine.lastSeenAt)
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31991234", originalExpiry))
    }

    @Test fun postCallLearningPreservesThirtyDayQuarantineInTheDatabaseAndCache() = runTest {
        val dao = dao()
        val scoring = engine(dao)
        val arrival = scoring.screenIncomingCallForTest("+5531991234567", "PASSED", nowMillis = now).telemetry
        val thirtyDays = now + 30L * 86_400_000
        dao.upsertQuarantine(quarantine(thirtyDays))
        QuarantinePrefixIndex.add("31991234", thirtyDays)
        scoring.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0,
            callTimestampMs = now, nowMillis = now + 10_000)
        assertEquals(thirtyDays, dao.getAllQuarantines().single().expiresAt)
        assertEquals(PrefixQuarantineMatch.EXACT8,
            QuarantinePrefixIndex.match("31991234", now + 8L * 86_400_000))
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31991234", thirtyDays))
    }
}
