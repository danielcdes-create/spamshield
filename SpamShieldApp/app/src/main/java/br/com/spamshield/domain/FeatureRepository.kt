package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeatureRepository @Inject constructor(
    private val dao: SpamShieldDao,
    private val settings: SettingsDataStore
) {
    private val writes = Mutex()
    private val replayWork = Mutex()

    suspend fun activeSilences(at: Long): List<CampaignSilence> =
        Campaigns.silences(dao.getFeatureEventsByType(Campaigns.silenceTypes)).filter { it.activeAt(at) }

    suspend fun shadowAt(at: Long): LabExperiment? =
        RuleLaboratory.active(dao.getFeatureEventsByType(RuleLaboratory.lifecycleTypes), at)

    suspend fun reconcile() = writes.withLock {
        val at = System.currentTimeMillis()
        settings.transitionExpectedWindow(at)
        val windowEventsFlushed = settings.flushWindowJournal(dao)
        val (calls, journal, features) = dao.getFullTelemetryExportSnapshot()
        val events = Campaigns.reconcile(calls, journal, Campaigns.projection(features), System.currentTimeMillis()).toMutableList()
        for (silence in Campaigns.silences(features).filter { it.expiresAt <= at }) {
            events += silenceEnd(silence, "CAMPAIGN_SILENCE_EXPIRED", at, silence.expiresAt)
        }
        val running = RuleLaboratory.running(features)
        if (running?.expiresAt != null && running.expiresAt <= at) {
            events += labEvent("LAB_ENDED", running, at, "TIME_LIMIT", effectiveAt = running.expiresAt)
        }
        val compared = features.filter { it.eventType == "LAB_COMPARISON" }.mapTo(hashSetOf()) { it.eventId }
        val experiments = features.filter { it.eventType == "LAB_SHADOW_STARTED" }
            .mapNotNull { FeatureJournal.payload(it).experiment }.associateBy { it.id }
        for (arrivals in originalDecisions(journal).groupBy { it.callId }.values) {
            val decision = arrivals.first()
            val details = DecisionTrace.details(decision)
            val experiment = experiments[details.experimentId]
                ?: details.evidenceAt?.let { RuleLaboratory.active(features, it) } ?: continue
            if (comparisonId(experiment, decision) !in compared) events += comparisonEvent(experiment, decision, at, arrivals.size > 1)
        }
        if (events.isNotEmpty()) dao.insertFeatureEvents(events)
        if (windowEventsFlushed || events.isNotEmpty()) TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun exportSnapshot() = writes.withLock {
        settings.flushWindowJournal(dao)
        dao.getFullTelemetryExportSnapshot()
    }

    suspend fun selector(id: String, opened: Boolean, window: ExpectedWindow?) = writes.withLock {
        val type = if (opened) "WAIT_SELECTOR_OPENED" else "WAIT_SELECTOR_DISMISSED"
        dao.insertFeatureEvents(listOf(FeatureJournal.event(type, FeatureJournal.DASHBOARD_SHIELD,
            window?.id ?: id, System.currentTimeMillis(), FeaturePayload(window = window, selectorId = id, reason = if (opened) "USER_OPENED" else "USER_DISMISSED"), key = id)))
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun changeWindow(selectorId: String, minutes: Int?, keepExact: Boolean, restore: Boolean) = writes.withLock {
        settings.transitionExpectedWindow(System.currentTimeMillis(), selectorId, minutes, keepExact, restore)
        settings.flushWindowJournal(dao)
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun feedback(callId: String, expected: Boolean) = writes.withLock {
        val journal = dao.getDecisionEvents(callId)
        val decision = originalDecisions(journal).singleOrNull() ?: return@withLock
        val policy = DecisionTrace.details(decision).policy ?: return@withLock
        if (policy.reason != "EXPECTED_CALL_WINDOW" || policy.windowId == null ||
            journal.none { it.eventType == "SCREENING_RESPONSE_SUBMITTED" && it.source == decision.source &&
                DecisionTrace.details(it).policy?.reason == "EXPECTED_CALL_WINDOW" }
        ) return@withLock
        val feedback = if (expected) "EXPECTED_CALL_CONFIRMED" else "SPAM_CONFIRMED"
        val previous = dao.getFeatureEventsByType(listOf("WAIT_FEEDBACK")).lastOrNull { it.callId == callId }
        if (previous?.let(FeatureJournal::payload)?.reason == feedback) return@withLock
        dao.insertFeatureEvents(listOf(FeatureJournal.event(
            "WAIT_FEEDBACK", "AUDIT_USER", policy.windowId, System.currentTimeMillis(),
            FeaturePayload(reason = feedback,
                decisionEventId = decision.eventId), callId
        )))
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun changeSilence(campaignId: String, minutes: Int?) = writes.withLock {
        require(minutes == null || minutes in ExpectedWindow.durations)
        val features = dao.getFeatureEventsByType(Campaigns.membershipTypes + Campaigns.silenceTypes)
        val campaign = Campaigns.projection(features).find { it.id == campaignId } ?: return@withLock
        val at = System.currentTimeMillis()
        val previous = Campaigns.silences(features).filter { it.prefix == campaign.prefix && it.activeAt(at) }
        val events = previous.map { silenceEnd(it, "CAMPAIGN_SILENCE_REMOVED", at, at) }.toMutableList()
        if (minutes != null) {
            val silence = CampaignSilence(java.util.UUID.randomUUID().toString(), campaign.id, campaign.prefix, at, at + minutes * 60_000L)
            events += FeatureJournal.event("CAMPAIGN_SILENCE_ACTIVATED", "AUDIT_USER", campaign.id, at,
                FeaturePayload(silence = silence), key = silence.id)
        }
        if (events.isNotEmpty()) dao.insertFeatureEvents(events)
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    private fun silenceEnd(silence: CampaignSilence, type: String, at: Long, effectiveAt: Long) =
        FeatureJournal.event(type, if (type.endsWith("EXPIRED")) "CLOCK" else "AUDIT_USER",
            silence.campaignId, at, FeaturePayload(silence = silence), effectiveAt = effectiveAt, key = silence.id)

    suspend fun startLab(change: RuleChange, shadowMinutes: Int?): LabExperiment {
        require(shadowMinutes == null || shadowMinutes in ExpectedWindow.durations)
        require(change.weight in -1000..1000)
        val currentRules = dao.observeAllRules().first()
        val config = DecisionTrace.configuration((currentRules + RuleLaboratory.catalog(currentRules)).distinctBy { it.ruleId })
        val settingsAtStart = settings.getSnapshot()
        val base = config.first { it.ruleId == change.ruleId }
        fun fingerprint(rules: List<EvaluatedRule>) = DecisionTrace.configurationFingerprint(rules, settingsAtStart.blockThresholdScore,
            settingsAtStart.shadowModeEnabled, settingsAtStart.userDdd, settingsAtStart.hideBlockedFromSystemCallLog)
        val baseFingerprint = fingerprint(config)
        val candidateFingerprint = fingerprint(RuleLaboratory.candidate(config, change))
        fun experiment(at: Long) = LabExperiment(java.util.UUID.randomUUID().toString(), change, at,
            shadowMinutes?.let { at + it * 60_000L }, RuleChange(base.ruleId, base.weight, base.enabled),
            baseFingerprint, candidateFingerprint)
        val started = if (shadowMinutes == null) {
            val originals = originalDecisions(dao.getAllDecisionEvents())
            val at = System.currentTimeMillis()
            val replay = experiment(at)
            dao.insertFeatureEvents(listOf(labEvent("LAB_REPLAY_STARTED", replay, at,
                originalDecisionIds = originals.map { it.eventId })))
            resumeReplays()
            replay
        } else {
            writes.withLock {
                val previous = RuleLaboratory.running(dao.getFeatureEventsByType(RuleLaboratory.lifecycleTypes))
                val at = System.currentTimeMillis()
                val shadow = experiment(at)
                dao.insertFeatureEvents(listOfNotNull(
                    previous?.let { labEvent("LAB_ENDED", it, at,
                        if (it.expiresAt != null && it.expiresAt <= at) "TIME_LIMIT" else "REPLACED",
                        effectiveAt = it.expiresAt?.coerceAtMost(at) ?: at) },
                    labEvent("LAB_SHADOW_STARTED", shadow, at)
                ))
                shadow
            }
        }
        TelemetrySyncScheduler.enqueueImmediateSync()
        return started
    }

    suspend fun resumeReplays() = replayWork.withLock {
        val history = dao.getFeatureEventsByType(listOf("LAB_REPLAY_STARTED", "LAB_REPLAY_FINISHED", "LAB_DISCARDED"))
        val terminal = history.filter { it.eventType != "LAB_REPLAY_STARTED" }.mapTo(hashSetOf()) { it.resourceId }
        val pending = history.filter { it.eventType == "LAB_REPLAY_STARTED" && it.resourceId !in terminal }
        if (pending.isEmpty()) return@withLock
        val originals = originalDecisions(dao.getAllDecisionEvents()).associateBy { it.eventId }
        for (started in pending) {
            val details = FeatureJournal.payload(started)
            val experiment = details.experiment ?: continue
            val groups = details.originalDecisionIds.mapNotNull { originals[it] }.groupBy { it.callId }
            val events = groups.values.map { arrivals ->
                comparisonEvent(experiment, arrivals.first(), System.currentTimeMillis(), arrivals.size > 1)
            }
            writes.withLock {
                val closed = dao.getFeatureEventsByType(listOf("LAB_DISCARDED", "LAB_REPLAY_FINISHED"))
                    .any { it.resourceId == experiment.id }
                if (!closed) {
                    dao.insertFeatureEvents(events + labEvent("LAB_REPLAY_FINISHED", experiment, System.currentTimeMillis()))
                }
            }
        }
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun finishLab(experiment: LabExperiment, action: String) = writes.withLock {
        require(action in setOf("LAB_ENDED", "LAB_DISCARDED", "LAB_APPLIED"))
        val at = System.currentTimeMillis()
        val history = dao.getFeatureEventsByType(RuleLaboratory.lifecycleTypes + "LAB_REPLAY_FINISHED")
            .filter { it.resourceId == experiment.id }
        if (history.isEmpty() || history.any { it.eventType in setOf("LAB_DISCARDED", "LAB_APPLIED", action) }) return@withLock
        val event = labEvent(action, experiment, at, "EXPLICIT_USER_ACTION")
        if (action == "LAB_APPLIED") {
            val change = experiment.change
            val rule = RuleLaboratory.catalog(dao.observeAllRules().first()).first { it.ruleId == change.ruleId }
                .copy(weight = change.weight, enabled = change.enabled, updatedAt = at)
            val mutation = SyncMutationEntity(event.eventId, "RULE_UPSERT", rule.ruleId, FeatureJournal.json.encodeToString(rule), at)
            dao.applyLabRule(rule, mutation, event)
        } else dao.insertFeatureEvents(listOf(event))
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun compareShadow(experiment: LabExperiment, callId: String, at: Long) {
        val event = originalDecisions(dao.getDecisionEvents(callId)).singleOrNull() ?: return
        if (DecisionTrace.details(event).experimentId == experiment.id) {
            dao.insertFeatureEvents(listOf(comparisonEvent(experiment, event, at)))
        }
    }

    private fun originalDecisions(journal: List<CallDecisionEventEntity>) =
        journal.filter { it.source == "CALL_SCREENING_SERVICE" && it.eventType == "SCREENING_DECISION" }

    private fun labEvent(
        type: String, experiment: LabExperiment, at: Long, reason: String? = null,
        effectiveAt: Long = at, originalDecisionIds: List<String> = emptyList()
    ) = FeatureJournal.event(type, "RULE_LAB", experiment.id, at,
        FeaturePayload(experiment = experiment, reason = reason, originalDecisionIds = originalDecisionIds),
        effectiveAt = effectiveAt, key = experiment.id)

    private fun comparisonId(experiment: LabExperiment, decision: CallDecisionEventEntity) =
        FeatureJournal.stableId("LAB_COMPARISON|${experiment.id}|${decision.eventId}")

    private fun comparisonEvent(experiment: LabExperiment, decision: CallDecisionEventEntity, at: Long, ambiguous: Boolean = false) =
        FeatureJournal.event("LAB_COMPARISON", if (experiment.expiresAt == null) "REPLAY" else "PROSPECTIVE_SHADOW",
            experiment.id, at, FeaturePayload(comparison = RuleLaboratory.compare(decision, experiment.change, ambiguous)),
            decision.callId, effectiveAt = DecisionTrace.details(decision).callAt ?: decision.recordedAt, key = decision.eventId)
}
