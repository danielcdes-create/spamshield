package br.com.spamshield.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.service.ScreeningDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeatureJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private val t = 1_790_000_000_000L
    private val signals = listOf(
        TriggeredRuleDto("NOT_IN_CONTACTS", 25, null),
        TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", 15, null),
        TriggeredRuleDto("PREFIX5_CHIPEIRA_FARM", 35, null)
    )

    @Test
    fun dataStoreRestartsWithWindowAndDurableOutboxThenRecordsExactExpiryOnce() = runTest {
        val path = folder.newFolder().resolve("wait.preferences_pb")
        var storeJob: Job = SupervisorJob()
        fun open() = SettingsDataStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext.minusKey(Job) + storeJob),
            produceFile = { path }
        ))
        var settings = open()
        val dao = FakeSpamShieldDao()
        val features = FeatureRepository(dao, settings)
        val selector = FeatureJournal.stableId("selector-fixture")
        features.selector(selector, true, null)
        features.selector(selector, true, null)
        features.selector(selector, false, null)
        assertNull(settings.getSnapshot().expectedWindow)
        assertEquals(2, dao.getFeatureEvents().size)

        for (duration in ExpectedWindow.durations) {
            settings.transitionExpectedWindow(t, selector, duration, true)
            val window = settings.getSnapshot().expectedWindow!!
            assertEquals(duration * 60_000L, window.expiresAt - t)
            assertTrue(window.activeAt(window.expiresAt - 1))
            assertFalse(window.activeAt(window.expiresAt))
        }
        val latest = settings.getSnapshot().expectedWindow!!
        storeJob.cancelAndJoin()
        storeJob = SupervisorJob()
        settings = open()
        assertEquals(latest, settings.getSnapshot().expectedWindow)
        settings.transitionExpectedWindow(latest.expiresAt + 45)
        settings.flushWindowJournal(dao)
        settings.flushWindowJournal(dao)
        val entries = dao.getFeatureEvents()
        assertEquals(1, entries.count { it.eventType == "WAIT_ACTIVATED" })
        assertEquals(3, entries.count { it.eventType == "WAIT_RENEWED" })
        val expired = entries.single { it.eventType == "WAIT_EXPIRED" }
        assertEquals(latest.expiresAt, expired.effectiveAt)
        assertEquals(latest.expiresAt + 45, expired.recordedAt)
        assertNull(settings.getSnapshot().expectedWindow)

        settings.transitionExpectedWindow(latest.expiresAt + 60, selector, 15, false)
        settings.transitionExpectedWindow(latest.expiresAt + 70, selector, restore = true)
        settings.flushWindowJournal(dao)
        assertNull(settings.getSnapshot().expectedWindow)
        assertEquals(1, dao.getFeatureEvents().count { it.eventType == "WAIT_RESTORED" })
        storeJob.cancelAndJoin()
    }

    @Test
    fun expectedWindowAndCampaignRespectProtectedActionsAndGlobalShadow() {
        val window = ExpectedWindow.start(t, 15, true)
        val active = CampaignSilence("silence", "campaign", "11999100", t, t + 300_000)
        val open = ScreeningPolicy.resolve("BLOCKED", signals, PrefixQuarantineMatch.NONE, window, t, active)
        assertEquals(75, HeuristicScorer.equation(signals))
        assertEquals("BLOCK", open.normalAction)
        assertEquals("ALLOW", open.chosenAction)
        assertEquals("EXPECTED_CALL_WINDOW", open.reason)
        assertEquals("BLOCK", ScreeningPolicy.resolve("BLOCKED", signals, PrefixQuarantineMatch.NONE,
            window, window.expiresAt).chosenAction)
        val exact = signals + TriggeredRuleDto("PREFIX_QUARANTINE_HIT", 70, null)
        assertEquals("BLOCK", ScreeningPolicy.resolve("BLOCKED", exact, PrefixQuarantineMatch.EXACT8, window, t).chosenAction)
        assertEquals("ALLOW", ScreeningPolicy.resolve("BLOCKED", exact, PrefixQuarantineMatch.EXACT8,
            window.copy(keepExactQuarantine = false), t).chosenAction)
        assertEquals("BLOCK", ScreeningPolicy.resolve("BLOCKED", exact, PrefixQuarantineMatch.BROAD7,
            window.copy(keepExactQuarantine = false), t).chosenAction)
        val strong = signals + TriggeredRuleDto("STIR_SHAKEN_FAILED", 85, null)
        assertEquals("BLOCK", ScreeningPolicy.resolve("BLOCKED", strong, PrefixQuarantineMatch.NONE, window, t, active).chosenAction)
        for (outcome in listOf("ALLOWED_CONTACT", "ALLOWED_EMERGENCY_REPEAT", "ALLOWED_OUTBOUND_GRACE", "SHADOW_BLOCKED")) {
            assertEquals("ALLOW", ScreeningPolicy.resolve(outcome, signals, PrefixQuarantineMatch.NONE, window, t, active).chosenAction)
        }
        assertEquals("ALLOW", ScreeningPolicy.resolve("ALLOWED_LOW_SCORE", signals,
            PrefixQuarantineMatch.NONE, window, t, active, shadowMode = true).chosenAction)
        assertEquals("SILENCE", ScreeningPolicy.resolve("ALLOWED_LOW_SCORE", signals,
            PrefixQuarantineMatch.NONE, window, t, active).chosenAction)
        assertEquals("ALLOW", ScreeningPolicy.resolve("ALLOWED_LOW_SCORE", signals, PrefixQuarantineMatch.NONE,
            null, t, neighboringSilence = active).chosenAction)
    }

    @Test
    fun campaignsKeepRedialsMergeAndSeparateRequestsFromAndroidAuthorship() = runTest {
        val members = listOf(row(1, 0), row(2, 40), row(3, 80), row(4, 120), row(5, 175), row(6, 176, 5))
        val copies = listOf(row(7, 175, 5), row(8, 50).copy(outcome = "OUTGOING_CALL"), row(9, 130))
        val journal = members.flatMapIndexed { index, row ->
            buildList {
                add(event(row, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(disallowCall = true)))
                if (index < 4) add(event(row, "CALL_LOG_OBSERVATION", DecisionDetails(systemCallType = 6,
                    systemBlockReason = 1, systemScreeningAppIsSpamShield = index < 3), "ANDROID_CALL_LOG"))
            }.filterNot { index == 5 && it.eventType == "SCREENING_RESPONSE_SUBMITTED" }
        } + event(members.last(), "CALL_LOG_OBSERVATION", DecisionDetails(systemCallType = 3), "ANDROID_CALL_LOG") +
            event(copies.first(), "CALL_LOG_OBSERVATION", DecisionDetails(systemCallType = 3), "ANDROID_CALL_LOG") +
            event(copies[1], "CALL_LOG_OBSERVATION", DecisionDetails(systemCallType = 2), "ANDROID_CALL_LOG") +
            event(copies.last(), "REPROCESS_EVALUATION", DecisionDetails(), "LOCAL_REPROCESS")
        val initial = Campaigns.reconcile(members.take(3), journal, emptyList(), t + 90_000)
        val initialCampaign = Campaigns.projection(initial).single()
        val updated = Campaigns.reconcile(members + copies, journal, listOf(initialCampaign), t + 180_000)
        val latest = Campaigns.projection(initial + updated).single()
        assertEquals(initialCampaign.id, latest.id)
        assertEquals(t + 80_000, latest.criterionSatisfiedAt)
        assertEquals(t + 90_000, latest.detectedAt)
        assertEquals(6, latest.memberIds.size)
        assertEquals(5, latest.distinctNumbers)
        assertTrue(Campaigns.reconcile(members + copies, journal, listOf(latest), t + 181_000).isEmpty())
        val counts = FeatureExport.summarize(members + copies, journal, initial + updated).campaigns.single()
        assertEquals(5, counts.blockRequests)
        assertEquals(3, counts.blockConfirmations)

        val dao = FakeSpamShieldDao()
        dao.insertFeatureEvents(initial + updated)
        val repo = FeatureRepository(dao, SettingsDataStore())
        repo.changeSilence(latest.id, 15)
        val silence = repo.activeSilences(System.currentTimeMillis()).single()
        assertEquals(latest.id, silence.campaignId)
        assertFalse(silence.activeAt(silence.expiresAt))
        repo.changeSilence(latest.id, null)
        assertTrue(repo.activeSilences(System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun lateEvidenceMergesOverlappingCampaignsAndKeepsEarliestDetectionIdentity() {
        val left = listOf(row(1, 0), row(2, 20), row(3, 50))
        val right = listOf(row(4, 430), row(5, 460), row(6, 480))
        fun journal(calls: List<CallTelemetryEntity>) = calls.map {
            event(it, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(disallowCall = false))
        }
        val first = Campaigns.reconcile(left, journal(left), emptyList(), t + 80_000)
        val second = Campaigns.reconcile(left + right, journal(left + right), Campaigns.projection(first), t + 500_000)
        val before = Campaigns.projection(first + second)
        assertEquals(2, before.size)
        val all = left + right + listOf(row(7, 190), row(8, 240), row(9, 270))
        val merged = Campaigns.reconcile(all, journal(all), before, t + 600_000)
        assertEquals(1, merged.count { it.eventType == "CAMPAIGN_MERGED" })
        assertEquals(Campaigns.projection(first).single().id, Campaigns.projection(first + second + merged).single().id)
    }

    @Test
    fun replayReconstructsSeventyFiveToFortyAndDoesNotChangeRulesUntilExplicitApply() = runTest {
        val dao = FakeSpamShieldDao()
        val repo = FeatureRepository(dao, SettingsDataStore())
        val change = RuleChange("PREFIX5_CHIPEIRA_FARM", 35, false)
        val original = row(1, 0)
        dao.insertTelemetry(original)
        val first = arrival(original)
        dao.insertDecisionEvent(first)
        val legacy = row(2, 20)
        dao.insertTelemetry(legacy)
        dao.insertDecisionEvent(event(legacy, "SCREENING_DECISION", DecisionDetails(score = 75, threshold = 70, outcome = "BLOCKED")))
        val experiment = repo.startLab(change, null)
        val comparisons = dao.getFeatureEvents().mapNotNull { FeatureJournal.payload(it).comparison }
        assertEquals(listOf("BLOCK_TO_ALLOW", "INSUFFICIENT"), comparisons.map { it.direction })
        assertEquals(40, comparisons.first().candidateScore)
        assertEquals(75, comparisons.first().baseScore)
        assertEquals("UNCHANGED", RuleLaboratory.compare(first, change.copy(weight = 40, enabled = true)).direction)
        assertEquals(75, DecisionTrace.details(dao.getDecisionEvents(original.id).single()).score)
        assertTrue(dao.rules.none { it.ruleId == change.ruleId })
        repo.finishLab(experiment, "LAB_APPLIED")
        repo.finishLab(experiment, "LAB_APPLIED")
        assertFalse(dao.rules.single { it.ruleId == change.ruleId }.enabled)
        assertEquals(1, dao.syncMutations.count { it.kind == "RULE_UPSERT" })
    }

    @Test
    fun voipWinnerIsReevaluatedWhenCandidateDisablesExclusiveRule() {
        val call = row(1, 0).copy(normalizedNumber = "+553141011234", rawNumber = "+553141011234")
        val base = listOf(
            EvaluatedRule("REGEX_MG_VOIP_TRUNKS", "REGEX_MATCH", "^\\+55314101\\d{4}$", 100, 10),
            EvaluatedRule("ANATEL_PABX_VOIP_RANGE", "HEURISTIC_WEIGHT", null, 55, 100)
        )
        val decision = event(call, "SCREENING_DECISION", DecisionTrace.snapshot(call).copy(
            callAt = t, threshold = 70, score = 125, stirShakenStatus = "PASSED", shadowMode = false,
            quarantineMatch = "NONE", rules = listOf(signals.first(), TriggeredRuleDto("REGEX_MG_VOIP_TRUNKS", 100, null)),
            evaluatedRules = base, ruleSetFingerprint = "old", arrivalContext = ArrivalContext("VoIP", "FIXED_OR_VOIP", "31", false, emptyList())
        ))
        val result = RuleLaboratory.compare(decision, RuleChange("REGEX_MG_VOIP_TRUNKS", 100, false))
        assertEquals("UNCHANGED", result.direction)
        assertTrue(result.candidateRules.any { it.rule_id in HeuristicScorer.VOIP_TRUNK_GROUP })
        assertTrue(result.candidateScore!! >= 70)
    }

    @Test
    fun interruptedReplayResumesOnlyItsOriginalUniverseAndOnlyOnce() = runTest {
        val dao = FakeSpamShieldDao()
        val first = arrival(row(1, 0))
        val later = arrival(row(2, 10))
        val proposed = RuleChange("PREFIX5_CHIPEIRA_FARM", 35, false)
        val replay = LabExperiment(FeatureJournal.stableId("pending-replay"), proposed, t, null,
            proposed.copy(enabled = true), "base", "candidate")
        dao.insertDecisionEvent(first)
        dao.insertFeatureEvents(listOf(FeatureJournal.event("LAB_REPLAY_STARTED", "RULE_LAB", replay.id, t,
            FeaturePayload(experiment = replay, originalDecisionIds = listOf(first.eventId)))))
        dao.insertDecisionEvent(later)
        repeat(2) { FeatureRepository(dao, SettingsDataStore()).resumeReplays() }
        val features = dao.getFeatureEvents()
        assertEquals(1, features.count { it.eventType == "LAB_REPLAY_FINISHED" })
        assertEquals(first.eventId, features.mapNotNull { FeatureJournal.payload(it).comparison }.single().originalEventId)
    }

    @Test
    fun hypotheticalBlockReleasedByWindowDoesNotInstallQuarantine() = runTest {
        val dao = FakeSpamShieldDao()
        val engine = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())
        val call = row(1, 0)
        val window = ExpectedWindow.start(t, 15, true)
        val decision = ScreeningPolicy.resolve("BLOCKED", signals, PrefixQuarantineMatch.NONE, window, t)
        val prepared = PreparedScreening(CallEvaluationResult(call, true, true, false, false), window) {
            DecisionTrace.details(arrival(call, policy = decision))
        }
        val before = dao.quarantines.toList()
        engine.recordIncomingCall(prepared, t + 10, DecisionDetails(disallowCall = false, policy = decision))
        assertEquals(before, dao.quarantines)
        assertFalse(dao.getDecisionEvents(call.id).any { it.eventType == "QUARANTINE_UPDATED" })
    }

    @Test
    fun reconstructsIndependentWindowReleaseFeedbackShadowAndTimeoutFromExport() = runTest {
        val dao = FakeSpamShieldDao()
        val repo = FeatureRepository(dao, SettingsDataStore())
        val waitStart = System.currentTimeMillis() - 1000
        val window = ExpectedWindow.start(waitStart, 30, true)
        val noCallWindow = ExpectedWindow.start(waitStart - 2_000_000, 15, true)
        val independent = listOf(noCallWindow, window).map {
            FeatureJournal.event("WAIT_ACTIVATED", FeatureJournal.DASHBOARD_SHIELD, it.id, it.startedAt,
                FeaturePayload(window = it), key = it.id)
        }
        dao.insertFeatureEvents(independent)
        val emptyExport = Json.parseToJsonElement(TelemetryExport.encodeSummary(emptyList(), emptyList(),
            featureEvents = listOf(independent.first()))).jsonObject
        assertEquals("0", emptyExport.getValue("rowCount").jsonPrimitive.content)
        assertTrue(emptyExport.toString().contains(noCallWindow.id))

        val experiment = repo.startLab(RuleChange("PREFIX5_CHIPEIRA_FARM", 35, false), 30)
        val call = row(1, 0).copy(timestamp = experiment.startedAt)
        val second = row(2, 20).copy(timestamp = experiment.startedAt)
        val incomplete = row(4, 30).copy(timestamp = experiment.startedAt)
        val fallback = row(3, 30).copy(timestamp = experiment.startedAt)
        val policy = ScreeningPolicy.resolve("BLOCKED", signals, PrefixQuarantineMatch.NONE, window, call.timestamp)
        val original = arrival(call, experiment.id, policy)
        dao.insertTelemetry(call)
        dao.insertDecisionEvent(original)
        dao.insertDecisionEvent(event(call, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(
            callAt = call.timestamp, policy = policy, disallowCall = false, elapsedMs = 12.0)))
        repo.feedback(call.id, true)
        repo.compareShadow(experiment, call.id, call.timestamp + 10)
        repo.compareShadow(experiment, call.id, call.timestamp + 11)

        val secondOriginal = arrival(second, experiment.id, verificationWeight = 20)
        dao.insertTelemetry(second)
        dao.insertDecisionEvent(secondOriginal)
        repo.compareShadow(experiment, second.id, second.timestamp + 10)
        dao.insertTelemetry(incomplete)
        dao.insertDecisionEvent(arrival(incomplete, experiment.id, missingContext = true))
        repo.compareShadow(experiment, incomplete.id, incomplete.timestamp + 10)
        dao.insertTelemetry(fallback)
        dao.insertDecisionEvent(event(fallback, "SCREENING_TIMEOUT", DecisionDetails(reason = "FALLBACK_AFTER_TIMEOUT")))
        dao.insertDecisionEvent(event(fallback, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(callAt = fallback.timestamp,
            disallowCall = false, experimentId = experiment.id, elapsedMs = 3501.0,
            latency = ScreeningLatency(budgetMs = 3500, timedOut = true))))
        val campaign = Campaign(FeatureJournal.stableId("fixture-campaign"), "11999100", t - 300_000, t - 100_000,
            t - 100_000, t - 90_000, (10..12).map { row(it, 0).id }, 3)
        val silence = CampaignSilence(FeatureJournal.stableId("test-silence"), campaign.id, campaign.prefix, t, t + 900_000)
        dao.insertFeatureEvents(listOf(
            FeatureJournal.event("CAMPAIGN_DETECTED", "CAMPAIGN_RECONCILIATION", campaign.id, campaign.detectedAt,
                FeaturePayload(campaign = campaign)),
            FeatureJournal.event("CAMPAIGN_SILENCE_ACTIVATED", "AUDIT_USER", campaign.id, silence.startedAt,
                FeaturePayload(silence = silence), key = silence.id)
        ))
        val silenced = row(5, 120)
        val nearby = row(6, 130)
        dao.insertTelemetry(silenced)
        dao.insertTelemetry(nearby)
        val silencePolicy = ScreeningPolicy.resolve("ALLOWED_LOW_SCORE", signals, PrefixQuarantineMatch.NONE, null, silenced.timestamp, silence)
        val neighborPolicy = ScreeningPolicy.resolve("ALLOWED_LOW_SCORE", signals, PrefixQuarantineMatch.NONE, null,
            nearby.timestamp, neighboringSilence = silence)
        dao.insertDecisionEvent(event(silenced, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(
            callAt = silenced.timestamp, policy = silencePolicy, disallowCall = false, silenceCall = true)))
        dao.insertDecisionEvent(event(nearby, "SCREENING_RESPONSE_SUBMITTED", DecisionDetails(
            callAt = nearby.timestamp, policy = neighborPolicy, disallowCall = false, silenceCall = false)))
        repo.finishLab(experiment, "LAB_ENDED")
        val (rows, journal, resources) = repo.exportSnapshot()
        val metadata = TelemetryExportMetadata(exportId = "fixture-export")
        val export = TelemetryExport.encodeSummary(rows, journal, metadata, resources)
        val parsed = Json.decodeFromString<TelemetryExportSummary>(export)
        val summary = parsed.features!!
        assertEquals("fixture-export", parsed.exportMetadata.exportId)
        assertEquals(1, summary.expectedWindowReleases)
        assertEquals(1, summary.expectedWindowFeedback["EXPECTED_CALL_CONFIRMED"])
        assertEquals(1, summary.triageByOriginalVersion.getValue("UNKNOWN").timeouts)
        assertEquals(DurationStats(2, 4, 12.0, 3501.0, 3501.0), summary.latency["response"])
        assertEquals(1, summary.campaigns.single().futureSilenceRequests)
        assertEquals(1, summary.neighboringCampaignMatches)
        val shadow = summary.experiments.single()
        assertEquals(3, shadow.comparisons)
        assertEquals(4, shadow.eligibleScreenings)
        assertEquals(1, shadow.notCompared)
        assertEquals(3, shadow.directions.values.sum())
        assertEquals(3, shadow.directionsByBaseFingerprint.size)
        assertEquals(2, shadow.directions["BLOCK_TO_ALLOW"])
        assertEquals(1, shadow.directions["INSUFFICIENT"])
        val feedback = resources.single { it.eventType == "WAIT_FEEDBACK" }
        assertEquals(window.id, feedback.resourceId)
        assertEquals(original.eventId, FeatureJournal.payload(feedback).decisionEventId)
        assertEquals(call.id, feedback.callId)
        assertNull(repo.shadowAt(System.currentTimeMillis()))
        assertTrue(summary.events.none { it.details.toString().contains(call.normalizedNumber) })
        println("Fixture local: ${journal.size} eventos de chamada, ${resources.size} eventos de recursos; " +
            "JSON ${TelemetryExport.encode(rows, journal, metadata).toByteArray().size} bytes; resumo ${export.toByteArray().size} bytes.")
    }

    @Test
    fun monotonicMarksAndSingleClaimSurviveFailuresAfterResponse() {
        var nanos = 10_000_000L
        val clock = { nanos }
        val timing = ScreeningTiming(nanos, clock)
        val deadline = ScreeningDeadline(nanos, clock)
        val stages = listOf("QUEUED", "EVALUATION", "CONTEXT_READY", "RULES_READY", "POLICY_READY", "SUBMITTING", "SUBMITTED")
        stages.forEach { nanos += 2_000_000; timing.mark(it) }
        assertFalse(deadline.claim()!!.timedOut)
        repeat(2) { runCatching { error("simulated post-response failure") }; assertNull(deadline.claim()) }
        val span = timing.snapshot(3500, false)
        assertEquals(2.0, span.evaluationWaitMs)
        assertEquals(2.0, span.rulesMs)
        assertEquals(2.0, span.submissionMs)
        assertEquals(14.0, timing.elapsed(), 0.001)
    }

    private fun arrival(call: CallTelemetryEntity, experiment: String? = null, policy: ScreeningPolicyDecision? = null,
                        verificationWeight: Int = 15, missingContext: Boolean = false): CallDecisionEventEntity {
        val configuration = DecisionTrace.configuration(RuleLaboratory.catalog(FakeSpamShieldDao().rules))
            .map { if (it.ruleId == "STIR_SHAKEN_UNVERIFIED") it.copy(weight = verificationWeight) else it }
        val context = ArrivalContext("Móvel", "MOBILE", "11", false, listOf(signals.last()))
        return event(call, "SCREENING_DECISION", DecisionDetails(
            callAt = call.timestamp, evidenceAt = call.timestamp, rawNumber = call.rawNumber, normalizedNumber = call.normalizedNumber,
            score = 60 + verificationWeight, threshold = 70, outcome = "BLOCKED", shadowMode = false, stirShakenStatus = "NOT_VERIFIED",
            quarantineMatch = "NONE", rules = signals.map {
                if (it.rule_id == "STIR_SHAKEN_UNVERIFIED") it.copy(weight = verificationWeight) else it
            }, evaluatedRules = configuration,
            ruleSetFingerprint = if (missingContext) null else DecisionTrace.configurationFingerprint(configuration, 70, false, "11", false),
            arrivalContext = context.takeUnless { missingContext }, experimentId = experiment, policy = policy
        ))
    }

    private fun row(id: Int, seconds: Long, number: Int = id): CallTelemetryEntity {
        val value = "+5511999100${number.toString().padStart(3, '0')}"
        return CallTelemetryEntity(FeatureJournal.stableId("call-fixture-$id"), t + seconds * 1000, "fixture", value, value,
            "11", "11999100", null, "MOBILE", "NOT_VERIFIED", 75, 70, "BLOCKED", "UNKNOWN_UNVERIFIED",
            0.8, false, triggeredRulesJson = "[]")
    }

    private fun event(row: CallTelemetryEntity, type: String, details: DecisionDetails, source: String = "CALL_SCREENING_SERVICE") =
        DecisionTrace.buildEvent(row.id, type, source, details, row.timestamp + 1, key = "fixture")
}
