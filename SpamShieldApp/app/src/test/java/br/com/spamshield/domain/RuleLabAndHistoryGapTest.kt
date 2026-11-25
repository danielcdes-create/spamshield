package br.com.spamshield.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.service.reprocessStoredTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuleLabAndHistoryGapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore

    @Before
    fun setUp() {
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefsFile = File(tempFolder.newFolder("datastore"), "lab.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile }
        settings = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
    }

    private fun decisionEvent(
        id: String,
        callId: String,
        details: DecisionDetails,
        recordedAt: Long = 1_700_000_000_000L
    ): CallDecisionEventEntity = CallDecisionEventEntity(
        eventId = id,
        callId = callId,
        recordedAt = recordedAt,
        eventType = "SCREENING_DECISION",
        source = "CALL_SCREENING_SERVICE",
        appVersion = "1.4.1 (21)",
        detailsJson = FeatureJournal.json.encodeToString(details)
    )

    @Test
    fun `RuleLaboratory compare covers ambiguous, protected allow, legacy additive and reconstruction mismatch branches`() {
        val baseDetails = DecisionDetails(
            score = 80,
            threshold = 70,
            outcome = "BLOCKED",
            category = "ROBOCALL_PREFIX_CLUSTER",
            shadowMode = false,
            ruleSetFingerprint = "fp-legacy-1",
            rules = listOf(
                TriggeredRuleDto("NOT_IN_CONTACTS", 25),
                TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", 20),
                TriggeredRuleDto("PREFIX5_CHIPEIRA_FARM", 35)
            ),
            evaluatedRules = listOf(
                EvaluatedRule("PREFIX5_CHIPEIRA_FARM", "HEURISTIC_WEIGHT", null, 35, 100, true)
            )
        )
        val legacyEvent = decisionEvent("00000000-0000-4000-8000-000000000101", "call-legacy", baseDetails)

        val ambiguous = RuleLaboratory.compare(legacyEvent, RuleChange("PREFIX5_CHIPEIRA_FARM", 0, false), ambiguous = true)
        assertEquals("INSUFFICIENT", ambiguous.direction)
        assertEquals("AMBIGUOUS_ARRIVAL_DECISION", ambiguous.reason)

        val blockToAllow = RuleLaboratory.compare(legacyEvent, RuleChange("PREFIX5_CHIPEIRA_FARM", 35, false))
        assertEquals("BLOCK_TO_ALLOW", blockToAllow.direction)
        assertEquals(45, blockToAllow.candidateScore)
        assertEquals("ALLOW", blockToAllow.candidateAction)

        val allowDetails = baseDetails.copy(
            score = 65,
            outcome = "ALLOWED_LOW_SCORE",
            rules = listOf(
                TriggeredRuleDto("NOT_IN_CONTACTS", 25),
                TriggeredRuleDto("EXTERNAL_DDD_UNKNOWN", 40)
            ),
            evaluatedRules = listOf(
                EvaluatedRule("EXTERNAL_DDD_UNKNOWN", "HEURISTIC_WEIGHT", null, 40, 100, true)
            )
        )
        val allowToBlock = RuleLaboratory.compare(
            decisionEvent("00000000-0000-4000-8000-000000000102", "call-legacy-2", allowDetails),
            RuleChange("EXTERNAL_DDD_UNKNOWN", 60, true)
        )
        assertEquals("ALLOW_TO_BLOCK", allowToBlock.direction)
        assertEquals(85, allowToBlock.candidateScore)

        val protectedEvent = decisionEvent(
            "00000000-0000-4000-8000-000000000103",
            "call-contact",
            DecisionDetails(
                score = 0,
                threshold = 70,
                outcome = "ALLOWED_CONTACT",
                rules = listOf(TriggeredRuleDto("CONTACT_WHITELIST", -1000))
            )
        )
        val protectedCmp = RuleLaboratory.compare(protectedEvent, RuleChange("NOT_IN_CONTACTS", 90, true))
        assertEquals("UNCHANGED", protectedCmp.direction)
        assertEquals("PROTECTED_ALLOW", protectedCmp.reason)

        val fullContext = ArrivalContext("TIM", "MOBILE", "31", false, emptyList())
        val fullDetails = DecisionDetails(
            rawNumber = "031988887777",
            score = 45,
            threshold = 70,
            outcome = "ALLOWED_LOW_SCORE",
            shadowMode = false,
            quarantineMatch = "NONE",
            stirShakenStatus = "NOT_VERIFIED",
            ruleSetFingerprint = "fp-full",
            arrivalContext = fullContext,
            rules = listOf(
                TriggeredRuleDto("NOT_IN_CONTACTS", 25),
                TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", 20)
            ),
            evaluatedRules = listOf(
                EvaluatedRule("NOT_IN_CONTACTS", "HEURISTIC_WEIGHT", null, 25, 12, true),
                EvaluatedRule("STIR_SHAKEN_UNVERIFIED", "HEURISTIC_WEIGHT", null, 20, 11, true),
                EvaluatedRule("BROKEN_LAB_REGEX", "REGEX_MATCH", "[unclosed", 90, 25, true)
            )
        )
        val fullEvent = decisionEvent("00000000-0000-4000-8000-000000000104", "call-full", fullDetails)

        assertEquals(
            "RULE_CONFIGURATION_UNAVAILABLE",
            RuleLaboratory.compare(fullEvent, RuleChange("MISSING_RULE", 50, true)).reason
        )
        assertEquals(
            "PREFIX_MATCH_UNAVAILABLE",
            RuleLaboratory.compare(
                decisionEvent("00000000-0000-4000-8000-000000000105", "call-bad-match", fullDetails.copy(quarantineMatch = "BOGUS")),
                RuleChange("NOT_IN_CONTACTS", 50, true)
            ).reason
        )
        assertEquals(
            "BASE_RECONSTRUCTION_MISMATCH",
            RuleLaboratory.compare(
                decisionEvent("00000000-0000-4000-8000-000000000106", "call-mismatch", fullDetails.copy(score = 999)),
                RuleChange("NOT_IN_CONTACTS", 50, true)
            ).reason
        )
        val safeWithBrokenRegex = RuleLaboratory.compare(fullEvent, RuleChange("NOT_IN_CONTACTS", 60, true))
        assertEquals("ALLOW_TO_BLOCK", safeWithBrokenRegex.direction)
        assertEquals(80, safeWithBrokenRegex.candidateScore)
    }

    @Test
    fun `FeatureRepository reconciles expired silences and shadow experiments, replaces active lab and guards wait feedback`() = runBlocking {
        val dao = FakeSpamShieldDao()
        val features = FeatureRepository(dao, settings)
        val now = System.currentTimeMillis()

        val campaignId = "00000000-0000-4000-8000-000000000201"
        val silence = CampaignSilence(
            id = "00000000-0000-4000-8000-000000000202",
            campaignId = campaignId,
            prefix = "319876",
            startedAt = now - 120_000L,
            expiresAt = now - 1_000L
        )
        val expiredExp = LabExperiment(
            id = "00000000-0000-4000-8000-000000000203",
            change = RuleChange("NOT_IN_CONTACTS", 60, true),
            startedAt = now - 120_000L,
            expiresAt = now - 1_000L,
            baseRule = RuleChange("NOT_IN_CONTACTS", 25, true),
            baseFingerprint = "fp-base",
            candidateFingerprint = "fp-cand"
        )
        dao.insertFeatureEvents(
            listOf(
                FeatureJournal.event(
                    "CAMPAIGN_SILENCE_ACTIVATED",
                    "AUDIT_USER",
                    campaignId,
                    silence.startedAt,
                    FeaturePayload(silence = silence),
                    key = silence.id
                ),
                FeatureJournal.event(
                    "LAB_SHADOW_STARTED",
                    "RULE_LAB",
                    expiredExp.id,
                    expiredExp.startedAt,
                    FeaturePayload(experiment = expiredExp),
                    key = expiredExp.id
                )
            )
        )

        val callId = "00000000-0000-4000-8000-000000000204"
        val decisionId = "00000000-0000-4000-8000-000000000205"
        dao.insertDecisionEvent(
            decisionEvent(
                id = decisionId,
                callId = callId,
                recordedAt = now - 60_000L,
                details = DecisionDetails(
                    score = 0,
                    threshold = 70,
                    outcome = "ALLOWED_CONTACT",
                    evidenceAt = now - 60_000L,
                    experimentId = expiredExp.id,
                    rules = listOf(TriggeredRuleDto("CONTACT_WHITELIST", -1000))
                )
            )
        )

        features.reconcile()
        val afterReconcile = dao.getFeatureEvents()
        assertTrue(afterReconcile.any { it.eventType == "CAMPAIGN_SILENCE_EXPIRED" && it.resourceId == campaignId })
        assertTrue(afterReconcile.any {
            it.eventType == "LAB_ENDED" && it.resourceId == expiredExp.id && FeatureJournal.payload(it).reason == "TIME_LIMIT"
        })
        assertTrue(afterReconcile.any { it.eventType == "LAB_COMPARISON" && it.callId == callId })

        val firstShadow = features.startLab(RuleChange("NOT_IN_CONTACTS", 55, true), shadowMinutes = 15)
        val secondShadow = features.startLab(RuleChange("STIR_SHAKEN_UNVERIFIED", 45, true), shadowMinutes = 30)
        assertTrue(dao.getFeatureEvents().any {
            it.eventType == "LAB_ENDED" && it.resourceId == firstShadow.id && FeatureJournal.payload(it).reason == "REPLACED"
        })
        features.finishLab(secondShadow, "LAB_DISCARDED")
        assertTrue(dao.getFeatureEvents().any { it.eventType == "LAB_DISCARDED" && it.resourceId == secondShadow.id })

        features.feedback(callId, expected = true)
        assertTrue(dao.getFeatureEventsByType(listOf("WAIT_FEEDBACK")).isEmpty())

        val waitCallId = "00000000-0000-4000-8000-000000000206"
        val waitWinId = "00000000-0000-4000-8000-000000000207"
        val waitDecId = "00000000-0000-4000-8000-000000000208"
        val policy = ScreeningPolicyDecision("BLOCK", "ALLOW", "EXPECTED_CALL_WINDOW", windowId = waitWinId)
        dao.insertDecisionEvent(
            decisionEvent(
                id = waitDecId,
                callId = waitCallId,
                recordedAt = now - 5_000L,
                details = DecisionDetails(score = 90, threshold = 70, outcome = "BLOCKED", policy = policy)
            )
        )
        dao.insertDecisionEvent(
            CallDecisionEventEntity(
                eventId = "00000000-0000-4000-8000-000000000209",
                callId = waitCallId,
                recordedAt = now - 4_990L,
                eventType = "SCREENING_RESPONSE_SUBMITTED",
                source = "CALL_SCREENING_SERVICE",
                appVersion = "1.4.1 (21)",
                detailsJson = FeatureJournal.json.encodeToString(DecisionDetails(policy = policy))
            )
        )
        features.feedback(waitCallId, expected = true)
        features.feedback(waitCallId, expected = true)
        assertEquals(1, dao.getFeatureEventsByType(listOf("WAIT_FEEDBACK")).size)
    }

    @Test
    fun `HistoryCallScorer and multi-DDD screening respect configured userDdd outside MG`() = runBlocking {
        val scored = HistoryCallScorer.score(
            input = ScoreInput(
                rawNumber = "021998887777",
                normalizedNumber = "+5521998887777",
                ddd = "21",
                prefix7 = "21998887",
                subscriberNumber = "998887777",
                carrierGuess = "Vivo",
                lineType = "MOBILE",
                stirShakenStatus = "NOT_VERIFIED",
                historicalUnverified = true,
                userLocalDdd = "11"
            ),
            userFeedback = "CONFIRMED_SPAM",
            prefix5ChipeiraRule = TriggeredRuleDto("PREFIX5_CHIPEIRA_FARM", 35, "chipeira")
        )
        assertEquals("USER_BLOCKED_RETROACTIVE", scored.outcome)
        assertEquals("USER_REPORTED_SPAM", scored.category)
        assertTrue(scored.triggeredRules.any { it.rule_id == "EXTERNAL_DDD_UNKNOWN" })
        assertTrue(scored.triggeredRules.any { it.rule_id == "PREFIX5_CHIPEIRA_FARM" })
        assertTrue(scored.triggeredRules.any { it.rule_id == UserFeedbackAudit.OVERRIDE_RULE })

        val dao = FakeSpamShieldDao().apply { quarantines.clear() }
        settings.setUserDdd("11")
        val now = 1_700_000_500_000L
        dao.upsertOutboundGrace(
            OutboundGraceEntity(
                normalizedNumber = "+5511987654321",
                prefix6 = "119876",
                expiresAt = now + SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS
            )
        )
        val engine = SpamScoringEngine(dao, settings, FakeContactLookup())

        val incomingLocalSp = engine.evaluateIncomingCall("987654321", "NOT_VERIFIED", now)
        assertFalse(incomingLocalSp.shouldDisallowAndReject)
        assertEquals("+5511987654321", incomingLocalSp.telemetry.normalizedNumber)
        assertEquals("11", incomingLocalSp.telemetry.ddd)
        assertEquals("ALLOWED_OUTBOUND_GRACE", incomingLocalSp.telemetry.outcome)

        dao.outboundGraces.clear()
        val screenedSp = engine.evaluateIncomingCall("976543210", "NOT_VERIFIED", now - 5_000L)
        dao.insertTelemetry(screenedSp.telemetry)
        assertEquals("+5511976543210", screenedSp.telemetry.normalizedNumber)
        assertEquals("ALLOWED_LOW_SCORE", screenedSp.telemetry.outcome)

        assertTrue(
            engine.evaluatePostCallLogVerdict(
                normalizedNumber = "976543210",
                callType = android.provider.CallLog.Calls.REJECTED_TYPE,
                callDurationSec = 0,
                nowMillis = now,
                rawNumber = "976543210"
            )
        )
        val reconciledSp = dao.getTelemetryById(screenedSp.telemetry.id)!!
        assertEquals("BLOCKED", reconciledSp.outcome)
        assertEquals("11976543", reconciledSp.prefix7)
        assertNotNull(dao.getActiveQuarantineForPrefix("11976543", now))

        dao.insertTelemetry(
            CallTelemetryEntity(
                id = "hist-local-sp",
                timestamp = now - 60_000L,
                deviceId = "s23-ultra-history",
                rawNumber = "32224444",
                normalizedNumber = "32224444",
                ddd = "",
                prefix7 = "",
                carrierGuess = "Fixo",
                lineType = "FIXED_OR_VOIP",
                stirShakenStatus = "NOT_VERIFIED",
                finalScore = 40,
                thresholdApplied = 70,
                outcome = "ALLOWED_LOW_SCORE",
                category = "UNKNOWN_UNVERIFIED",
                processingLatencyMs = 0.5,
                shadowModeActive = false,
                triggeredRulesJson = "[]"
            )
        )
        val updatedCount = reprocessStoredTelemetry(dao, settings)
        assertTrue(updatedCount >= 1)
        val reprocessed = dao.getTelemetryById("hist-local-sp")!!
        assertEquals("+551132224444", reprocessed.normalizedNumber)
        assertEquals("11", reprocessed.ddd)
        assertEquals("11322244", reprocessed.prefix7)
    }
}
