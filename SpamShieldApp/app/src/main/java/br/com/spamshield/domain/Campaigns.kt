package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import kotlinx.serialization.Serializable
import java.util.ArrayDeque

@Serializable
data class Campaign(
    val id: String,
    val prefix: String,
    val firstCallAt: Long,
    val lastCallAt: Long,
    val criterionSatisfiedAt: Long,
    val detectedAt: Long,
    val memberIds: List<String>,
    val distinctNumbers: Int,
    val criterionVersion: Int = 1
)

@Serializable
data class CampaignSilence(
    val id: String,
    val campaignId: String,
    val prefix: String,
    val startedAt: Long,
    val expiresAt: Long
) {
    fun activeAt(at: Long) = at in startedAt until expiresAt
}

object Campaigns {
    const val WINDOW_MS = 300_000L
    val silenceTypes = listOf("CAMPAIGN_SILENCE_ACTIVATED", "CAMPAIGN_SILENCE_REMOVED", "CAMPAIGN_SILENCE_EXPIRED")
    val membershipTypes = listOf("CAMPAIGN_DETECTED", "CAMPAIGN_UPDATED", "CAMPAIGN_MERGED")

    fun projection(events: List<FeatureEventEntity>): List<Campaign> {
        val campaigns = linkedMapOf<String, Campaign>()
        for (event in events.filter { it.eventType in membershipTypes }) {
            val payload = FeatureJournal.payload(event)
            payload.campaign?.let { campaigns[it.id] = it }
            if (event.eventType == "CAMPAIGN_MERGED") campaigns.remove(payload.previousId)
        }
        return campaigns.values.sortedByDescending { it.lastCallAt }
    }

    fun silences(events: List<FeatureEventEntity>): List<CampaignSilence> =
        events.filter { it.eventType in silenceTypes }.mapNotNull { FeatureJournal.payload(it).silence?.let { s -> s to it } }
            .groupBy { it.first.id }.values.mapNotNull { history ->
                history.last().takeIf { it.second.eventType == "CAMPAIGN_SILENCE_ACTIVATED" }?.first
            }

    fun reconcile(
        rows: List<CallTelemetryEntity>,
        journal: List<CallDecisionEventEntity>,
        existing: List<Campaign>,
        observedAt: Long
    ): List<FeatureEventEntity> {
        val knownIncoming = journal.filter {
            (it.source == "CALL_SCREENING_SERVICE" && it.eventType in setOf(
                "SCREENING_DECISION", "SCREENING_RESPONSE_SUBMITTED", "SCREENING_RESPONSE_FAILED"
            )) || (it.eventType == "CALL_LOG_OBSERVATION" && DecisionTrace.details(it).systemCallType in setOf(1, 3, 5, 6, 7))
        }.mapTo(hashSetOf()) { it.callId }
        // A screened row takes precedence over an imported copy with the same original occurrence.
        val screened = journal.filter { it.source == "CALL_SCREENING_SERVICE" }.mapTo(hashSetOf()) { it.callId }
        val candidates = rows.asSequence().filter { it.id in knownIncoming && it.outcome != "OUTGOING_CALL" }
            .filter { it.normalizedNumber.isNotBlank() && it.prefix7.length == 8 &&
                it.lineType !in setOf("MALFORMED_OR_SPOOFED", "TOLL_FREE_OR_SPECIAL") }
            .sortedWith(compareBy<CallTelemetryEntity> { it.id !in screened }.thenBy { it.id })
            .distinctBy { it.timestamp to it.normalizedNumber }.groupBy { it.prefix7 }
        val existingByPrefix = existing.groupBy { it.prefix }
        return buildList {
            for ((prefix, group) in candidates) {
                val ordered = group.sortedWith(compareBy({ it.timestamp }, { it.id }))
                var first = 0
                var range: IntRange? = null
                var criterionAt = 0L
                val counts = mutableMapOf<String, Int>()
                val active = ArrayDeque<CallTelemetryEntity>()

                fun emit() {
                    val members = range?.let { ordered.slice(it) } ?: return
                    val memberIds = members.map { it.id }
                    val memberSet = memberIds.toHashSet()
                    val ancestors = existingByPrefix[prefix].orEmpty().filter { old -> old.memberIds.any { it in memberSet } }
                        .sortedWith(compareBy({ it.detectedAt }, { it.id }))
                    val original = ancestors.firstOrNull()
                    val campaign = Campaign(
                        original?.id ?: FeatureJournal.stableId("campaign|$prefix|${members.first().id}"),
                        prefix, members.first().timestamp, members.last().timestamp, criterionAt,
                        original?.detectedAt ?: observedAt, memberIds, members.map { it.normalizedNumber }.distinct().size
                    )
                    if (campaign != original) {
                        add(FeatureJournal.event(if (original == null) "CAMPAIGN_DETECTED" else "CAMPAIGN_UPDATED",
                            "CAMPAIGN_RECONCILIATION", campaign.id, observedAt, FeaturePayload(campaign = campaign),
                            effectiveAt = campaign.criterionSatisfiedAt))
                    }
                    for (merged in ancestors.drop(1)) {
                        add(FeatureJournal.event("CAMPAIGN_MERGED", "CAMPAIGN_RECONCILIATION", campaign.id, observedAt,
                            FeaturePayload(previousId = merged.id), key = merged.id))
                    }
                }

                for ((index, call) in ordered.withIndex()) {
                    while (active.isNotEmpty() && call.timestamp - active.first.timestamp > WINDOW_MS) {
                        val number = active.removeFirst().normalizedNumber
                        val count = counts.getValue(number) - 1
                        if (count == 0) counts.remove(number) else counts[number] = count
                        first++
                    }
                    active.addLast(call)
                    counts.merge(call.normalizedNumber, 1, Int::plus)
                    if (counts.size < 3) continue
                    val previous = range
                    if (previous == null || first > previous.last) {
                        emit()
                        criterionAt = call.timestamp
                        range = first..index
                    } else {
                        range = previous.first..index
                    }
                }
                emit()
            }
        }
    }
}
