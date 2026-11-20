package br.com.spamshield.domain

import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.remote.toRemoteEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

@Serializable
data class TelemetryExportMetadata(
    val schemaVersion: Int = 2,
    val exportId: String = UUID.randomUUID().toString(),
    val exportedAt: Long = System.currentTimeMillis(),
    val exporterVersionName: String = BuildConfig.VERSION_NAME,
    val exporterVersionCode: Int = BuildConfig.VERSION_CODE,
    val engineRevision: String = DecisionTrace.SCREENING_ENGINE_REVISION,
    val timestampUnit: String = "UNIX_EPOCH_MILLISECONDS",
    val timezoneForIsoDates: String = "UTC",
    val rowTimestampSemantics: String = "CALL_TIME_NOT_PROCESSING_OR_INSTALL_TIME",
    val eventRecordedAtSemantics: String = "EVENT_RECORDED_BY_APP_VERSION_AT_THIS_TIME",
    val currentClassificationSemantics: String = "LATEST_MATCHING_JOURNAL_SNAPSHOT_NOT_ORIGINAL_CALL_DECISION",
    val originalVersionSemantics: String = "ONLY_SCREENING_DECISION_FROM_CALL_SCREENING_SERVICE",
    val legacySemantics: String = "MISSING_ORIGINAL_SCREENING_EVENT_MEANS_ORIGINAL_VERSION_UNKNOWN"
)

@Serializable
data class VersionPeriodSummary(
    val appVersion: AppVersionIdentity,
    val originalScreenedCalls: Int,
    val firstOriginalCallAt: Long? = null,
    val lastOriginalCallAt: Long? = null,
    val firstOriginalCallAtIso: String? = null,
    val lastOriginalCallAtIso: String? = null,
    val matchingCurrentClassificationRows: Int,
    val eventCount: Int,
    val firstEventRecordedAt: Long? = null,
    val lastEventRecordedAt: Long? = null
)

@Serializable
data class TelemetryExportSummary(
    val exportMetadata: TelemetryExportMetadata,
    val rowCount: Int,
    val eventCount: Int,
    val originalVersionUnknownRows: Int,
    val originalVersionAmbiguousRows: Int,
    val currentClassificationVersionUnknownRows: Int,
    val versions: List<VersionPeriodSummary>,
    val pendingEventCount: Int = 0,
    val features: FeatureExport? = null
)

object TelemetryExport {
    private val json = Json { encodeDefaults = true }

    /** Existing root array and fields stay compatible; each row gains its full journal. */
    fun encode(
        rows: List<CallTelemetryEntity>, events: List<CallDecisionEventEntity>,
        metadata: TelemetryExportMetadata = TelemetryExportMetadata()
    ): String {
        val byCall = events.groupBy { it.callId }
        return JsonArray(rows.map { row ->
            val own = byCall[row.id].orEmpty()
            JsonObject(json.encodeToJsonElement(row).jsonObject +
                ("decisionEvents" to json.encodeToJsonElement(own.map { it.toRemoteEvent() })) +
                ("decisionEventSync" to json.encodeToJsonElement(own.associate { it.eventId to it.syncStatus })) +
                ("provenance" to json.encodeToJsonElement(TelemetryProvenance.forCall(row, own))) +
                ("exportMetadata" to json.encodeToJsonElement(metadata)))
        }).toString()
    }

    fun summary(
        rows: List<CallTelemetryEntity>, events: List<CallDecisionEventEntity>,
        metadata: TelemetryExportMetadata = TelemetryExportMetadata(),
        featureEvents: List<FeatureEventEntity> = emptyList()
    ): TelemetryExportSummary {
        val rowIds = rows.map { it.id }.toSet()
        val includedEvents = events.filter { it.callId in rowIds }
        val byCall = includedEvents.groupBy { it.callId }
        val provenance = rows.associate { it.id to TelemetryProvenance.forCall(it, byCall[it.id].orEmpty()) }
        val versions = includedEvents.map(TelemetryProvenance::version).distinct()
            .sortedWith(compareBy({ it.versionCode ?: -1 }, { it.label })).map { version ->
                val originals = rows.filter { provenance[it.id]?.originalDecision?.appVersion == version }
                val versionEvents = includedEvents.filter { TelemetryProvenance.version(it) == version }
                val firstCall = originals.minOfOrNull { it.timestamp }
                val lastCall = originals.maxOfOrNull { it.timestamp }
                VersionPeriodSummary(
                    appVersion = version,
                    originalScreenedCalls = originals.size,
                    firstOriginalCallAt = firstCall,
                    lastOriginalCallAt = lastCall,
                    firstOriginalCallAtIso = firstCall?.let { Instant.ofEpochMilli(it).toString() },
                    lastOriginalCallAtIso = lastCall?.let { Instant.ofEpochMilli(it).toString() },
                    matchingCurrentClassificationRows = provenance.values.count { it.currentClassification?.appVersion == version },
                    eventCount = versionEvents.size,
                    firstEventRecordedAt = versionEvents.minOfOrNull { it.recordedAt },
                    lastEventRecordedAt = versionEvents.maxOfOrNull { it.recordedAt }
                )
            }
        return TelemetryExportSummary(
            metadata, rows.size, includedEvents.size,
            provenance.values.count { it.originalVersionStatus == "ORIGINAL_VERSION_UNKNOWN" },
            provenance.values.count { it.originalVersionStatus == "AMBIGUOUS_SCREENING_VERSIONS" },
            provenance.values.count { it.currentClassification == null }, versions,
            pendingEventCount = includedEvents.count { it.syncStatus == "PENDING" },
            features = FeatureExport.summarize(rows, includedEvents, featureEvents)
        )
    }

    fun encodeSummary(
        rows: List<CallTelemetryEntity>, events: List<CallDecisionEventEntity>,
        metadata: TelemetryExportMetadata = TelemetryExportMetadata(),
        featureEvents: List<FeatureEventEntity> = emptyList()
    ): String = json.encodeToString(summary(rows, events, metadata, featureEvents))
}
