package br.com.spamshield.domain

import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.entity.FeatureEventEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ExpectedWindow(
    val id: String,
    val startedAt: Long,
    val expiresAt: Long,
    val durationMinutes: Int,
    val keepExactQuarantine: Boolean
) {
    fun activeAt(at: Long) = at in startedAt until expiresAt

    companion object {
        val durations = listOf(15, 30, 60, 120)

        fun start(at: Long, minutes: Int, keepExact: Boolean): ExpectedWindow {
            require(minutes in durations)
            return ExpectedWindow(UUID.randomUUID().toString(), at, at + minutes * 60_000L, minutes, keepExact)
        }
    }
}

@Serializable
data class FeaturePayload(
    val window: ExpectedWindow? = null,
    val selectorId: String? = null,
    val reason: String? = null,
    val decisionEventId: String? = null,
    val previousId: String? = null,
    val campaign: Campaign? = null,
    val silence: CampaignSilence? = null,
    val experiment: LabExperiment? = null,
    val comparison: LabComparison? = null,
    val originalDecisionIds: List<String> = emptyList()
)

object FeatureJournal {
    val json = Json { ignoreUnknownKeys = true }
    const val DASHBOARD_SHIELD = "DASHBOARD_SHIELD"

    fun stableId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

    fun event(
        type: String,
        source: String,
        resourceId: String,
        at: Long,
        payload: FeaturePayload = FeaturePayload(),
        callId: String? = null,
        effectiveAt: Long = at,
        key: String = UUID.randomUUID().toString()
    ) = FeatureEventEntity(
        eventId = stableId("$type|$resourceId|$key"), callId = callId, resourceId = resourceId,
        eventType = type, source = source, recordedAt = at, effectiveAt = effectiveAt,
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        detailsJson = json.encodeToString(payload)
    )

    fun payload(event: FeatureEventEntity): FeaturePayload = json.decodeFromString(event.detailsJson)
}
