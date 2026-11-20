package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.remote.FeatureEventRemoteDto
import br.com.spamshield.data.remote.toRemoteEvent
import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class DurationStats(
    val valid: Int, val missing: Int, val median: Double?, val p95: Double?, val max: Double?
)

@Serializable
data class ActionCounts(
    val screened: Int,
    val requests: Map<String, Int>,
    val androidBlockConfirmations: Int,
    val androidAllowConfirmations: Int? = null,
    val androidSilenceConfirmations: Int? = null,
    val androidObservations: Int,
    val unknownAndroidOutcome: Int,
    val timeouts: Int,
    val evaluationErrors: Int,
    val responseErrors: Int
)

@Serializable
data class CampaignExport(
    val campaign: Campaign,
    val attempts: Int,
    val blockRequests: Int,
    val blockConfirmations: Int,
    val memberSilenceRequests: Int,
    val memberAllowRequests: Int,
    val memberUnknownObservations: Int,
    val futureSilenceRequests: Int,
    val neighboringShadowMatches: Int
)

@Serializable
data class LabExport(
    val experimentId: String,
    val origin: String,
    val comparisons: Int,
    val eligibleScreenings: Int,
    val notCompared: Int,
    val directions: Map<String, Int>,
    val insufficientReasons: Map<String, Int>,
    val directionsByBaseFingerprint: Map<String, Map<String, Int>>
)

@Serializable
data class FeatureExport(
    val events: List<FeatureEventRemoteDto>,
    val eventSync: Map<String, String>,
    val pendingByType: Map<String, Int>,
    val triageByOriginalVersion: Map<String, ActionCounts>,
    val latencyUnit: String = "ms",
    val percentileMethod: String = "nearest_rank",
    val latency: Map<String, DurationStats>,
    val windowsByDurationMinutes: Map<Int, Int>,
    val windowActivationsIncludingRenewals: Int,
    val expectedWindowReleases: Int,
    val triagesWithWindow: Int,
    val windowEligibleTriages: Int,
    val expectedWindowFeedback: Map<String, Int>,
    val campaigns: List<CampaignExport>,
    val campaignSilenceRequests: Int,
    val neighboringCampaignMatches: Int,
    val experiments: List<LabExport>
) {
    companion object {
        fun summarize(
            rows: List<CallTelemetryEntity>, journal: List<CallDecisionEventEntity>, features: List<FeatureEventEntity>
        ): FeatureExport {
            val ordered = compareBy<CallDecisionEventEntity>({ it.recordedAt }, { it.eventId })
            val byCall = journal.sortedWith(ordered).groupBy { it.callId }
            val rowsById = rows.associateBy { it.id }
            fun androidObserved(id: String) = byCall[id].orEmpty().lastOrNull { it.eventType == "CALL_LOG_OBSERVATION" }
                ?.let(DecisionTrace::details)?.systemCallType in setOf(1, 3, 4, 5, 6, 7)
            val evidence = rows.associate { it.id to DecisionTrace.evidence(it, byCall[it.id].orEmpty()) }
            val real = journal.filter { it.source == "CALL_SCREENING_SERVICE" }.sortedWith(ordered)
            val activity = real.groupBy { it.callId }.filterValues { own -> own.any {
                it.eventType in setOf("SCREENING_DECISION", "SCREENING_RESPONSE_SUBMITTED", "SCREENING_RESPONSE_FAILED")
            } }
            val successful = real.filter { it.eventType == "SCREENING_RESPONSE_SUBMITTED" }.associateBy { it.callId }
                .mapValues { DecisionTrace.details(it.value) }
            val submissions = real.filter { it.eventType in setOf("SCREENING_RESPONSE_SUBMITTED", "SCREENING_RESPONSE_FAILED") }
                .associateBy { it.callId }.mapValues { DecisionTrace.details(it.value) }
            val post = real.filter { it.eventType == "SCREENING_POST_RESPONSE" }.associateBy { it.callId }
                .mapValues { DecisionTrace.details(it.value).latency }
            val releaseIds = successful.filterValues { it.policy?.reason == "EXPECTED_CALL_WINDOW" }.keys
            val feedback = features.filter { it.eventType == "WAIT_FEEDBACK" }.associateBy { it.callId }
            val activations = features.filter { it.eventType in setOf("WAIT_ACTIVATED", "WAIT_RENEWED") }
            val data = features.map { it to FeatureJournal.payload(it) }
            val comparisons = data.filter { it.second.comparison != null }.groupBy { it.first.resourceId }
            val mergedInto = data.filter { it.first.eventType == "CAMPAIGN_MERGED" }
                .associate { it.second.previousId to it.first.resourceId }
            fun currentCampaign(id: String?): String? {
                var current = id
                val seen = hashSetOf<String?>()
                while (seen.add(current)) current = mergedInto[current] ?: return current
                return null
            }
            val campaignOverview = Campaigns.projection(features).map { campaign ->
                CampaignExport(campaign, campaign.memberIds.size,
                    campaign.memberIds.count { evidence[it]?.blockRequested == true },
                    campaign.memberIds.count { evidence[it]?.blockObserved == true },
                    campaign.memberIds.count { successful[it]?.silenceCall == true },
                    campaign.memberIds.count { successful[it]?.let { s -> s.disallowCall == false && s.silenceCall != true } == true },
                    campaign.memberIds.count { !androidObserved(it) },
                    successful.values.count { it.silenceCall == true && currentCampaign(it.policy?.campaignId) == campaign.id },
                    submissions.values.count { currentCampaign(it.policy?.neighboringCampaignId) == campaign.id })
            }

            fun metric(values: List<Double?>): DurationStats {
                val valid = values.filterNotNull().filter { it.isFinite() && it >= 0 }.sorted()
                fun percentile(p: Double) = if (valid.isEmpty()) null else valid[ceil(p * valid.size).toInt() - 1]
                return DurationStats(valid.size, values.size - valid.size, percentile(.5), percentile(.95), valid.lastOrNull())
            }
            fun countByVersion(): Map<String, ActionCounts> = activity.keys.groupBy { id ->
                rowsById[id]?.let { TelemetryProvenance.forCall(it, byCall[id].orEmpty()).originalDecision?.appVersion?.label } ?: "UNKNOWN"
            }.mapValues { (_, ids) ->
                val observed = ids.count { androidObserved(it) }
                val actions = ids.map { id ->
                    val response = successful[id]
                    when {
                        response?.disallowCall == true -> "BLOCK"
                        response?.silenceCall == true -> "SILENCE"
                        response?.disallowCall == false -> "ALLOW"
                        else -> "UNCONFIRMED_SUBMISSION"
                    }
                }
                ActionCounts(
                    screened = ids.size,
                    requests = listOf("BLOCK", "ALLOW", "SILENCE", "UNCONFIRMED_SUBMISSION")
                        .associateWith { action -> actions.count { it == action } },
                    androidBlockConfirmations = ids.count { evidence[it]?.blockObserved == true },
                    androidObservations = observed, unknownAndroidOutcome = ids.size - observed,
                    timeouts = ids.count { id -> activity[id].orEmpty().any { it.eventType == "SCREENING_TIMEOUT" } },
                    evaluationErrors = ids.count { id -> activity[id].orEmpty().any { it.eventType == "SCREENING_ERROR" } },
                    responseErrors = ids.count { id -> activity[id].orEmpty().any { it.eventType == "SCREENING_RESPONSE_FAILED" } }
                )
            }

            val metrics: Map<String, (ScreeningLatency) -> Double?> = mapOf(
                "callbackSetup" to { it.callbackSetupMs }, "evaluationWait" to { it.evaluationWaitMs },
                "localContext" to { it.localContextMs }, "rules" to { it.rulesMs }, "policy" to { it.policyMs },
                "submission" to { it.submissionMs }
            )
            return FeatureExport(
                events = features.map { it.toRemoteEvent() }, eventSync = features.associate { it.eventId to it.syncStatus },
                pendingByType = (journal.filter { it.syncStatus == "PENDING" }.map { it.eventType } +
                    features.filter { it.syncStatus == "PENDING" }.map { it.eventType }).groupingBy { it }.eachCount(),
                triageByOriginalVersion = countByVersion(),
                latency = metrics.mapValues { (_, value) -> metric(activity.keys.map { submissions[it]?.latency?.let(value) }) } +
                    mapOf("response" to metric(activity.keys.map { submissions[it]?.elapsedMs }),
                        "processingLatency" to metric(activity.keys.map { id ->
                            rowsById[id]?.processingLatencyMs?.takeIf { activity[id].orEmpty().any { it.eventType == "SCREENING_DECISION" } }
                        }),
                        "journal" to metric(activity.keys.map { post[it]?.journalMs }),
                        "candidate" to metric(activity.keys.map { post[it]?.candidateMs })),
                windowsByDurationMinutes = activations.mapNotNull { FeatureJournal.payload(it).window?.durationMinutes }
                    .groupingBy { it }.eachCount(),
                windowActivationsIncludingRenewals = activations.size,
                expectedWindowReleases = releaseIds.size,
                triagesWithWindow = submissions.values.count { it.policy?.windowId != null },
                windowEligibleTriages = submissions.values.count { it.policy?.windowEligible == true },
                expectedWindowFeedback = releaseIds.map { id ->
                    feedback[id]?.let(FeatureJournal::payload)?.reason ?: "UNRATED"
                }.groupingBy { it }.eachCount(),
                campaigns = campaignOverview,
                campaignSilenceRequests = successful.values.count { it.silenceCall == true && it.policy?.reason == "CAMPAIGN_SILENCE" },
                neighboringCampaignMatches = submissions.values.count { it.policy?.neighboringCampaignId != null },
                experiments = data.filter { it.first.eventType in setOf("LAB_REPLAY_STARTED", "LAB_SHADOW_STARTED") }.map { (event, _) ->
                    val distinct = comparisons[event.resourceId].orEmpty().mapNotNull { it.second.comparison }
                        .distinctBy { it.originalEventId }
                    val eligible = if (event.eventType == "LAB_REPLAY_STARTED") distinct.size else
                        activity.count { (_, events) -> events.any { DecisionTrace.details(it).experimentId == event.resourceId } }
                            .coerceAtLeast(distinct.size)
                    LabExport(event.resourceId, if (event.eventType == "LAB_REPLAY_STARTED") "REPLAY" else "PROSPECTIVE_SHADOW",
                        distinct.size, eligible, eligible - distinct.size, RuleLaboratory.directions.associateWith { key -> distinct.count { it.direction == key } },
                        distinct.filter { it.direction == "INSUFFICIENT" }.groupingBy { it.reason }.eachCount(),
                        distinct.groupBy { it.baseFingerprint ?: "UNKNOWN" }.mapValues { (_, values) ->
                            values.groupingBy { it.direction }.eachCount()
                        })
                }
            )
        }
    }
}
