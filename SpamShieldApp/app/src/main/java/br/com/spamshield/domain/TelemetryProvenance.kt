package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionIdentity(
    val label: String,
    val versionName: String? = null,
    val versionCode: Int? = null
)

@Serializable
data class ClassificationProvenance(
    val eventId: String,
    val eventType: String,
    val source: String,
    val recordedAt: Long,
    val appVersion: AppVersionIdentity,
    val engineRevision: String
)

@Serializable
data class CallVersionProvenance(
    val originalVersionStatus: String,
    val originalDecision: ClassificationProvenance? = null,
    val currentClassificationStatus: String,
    val currentClassification: ClassificationProvenance? = null,
    val eventAppVersions: List<AppVersionIdentity> = emptyList(),
    val callTimestampSemantics: String = "CALL_TIME_NOT_PROCESSING_OR_INSTALL_TIME"
)

object TelemetryProvenance {
    private val versionLabel = Regex("^(.+) \\((\\d+)\\)$")
    private val assessmentTypes = setOf(
        "SCREENING_DECISION", "POST_CALL_EVALUATION", "REPROCESS_EVALUATION",
        "HISTORY_ASSESSMENT", "USER_FEEDBACK"
    )

    fun version(event: CallDecisionEventEntity): AppVersionIdentity {
        val details = DecisionTrace.details(event)
        val parsed = versionLabel.matchEntire(event.appVersion)
        return AppVersionIdentity(
            label = event.appVersion.ifBlank { "UNKNOWN" },
            versionName = details.appVersionName ?: parsed?.groupValues?.get(1),
            versionCode = details.appVersionCode ?: parsed?.groupValues?.get(2)?.toIntOrNull()
        )
    }

    fun forCall(row: CallTelemetryEntity, events: List<CallDecisionEventEntity>): CallVersionProvenance {
        val own = events.filter { it.callId == row.id }
            .sortedWith(compareBy({ it.recordedAt }, { it.eventId }))
        val arrivals = own.filter {
            it.eventType == "SCREENING_DECISION" && it.source == "CALL_SCREENING_SERVICE"
        }
        val original = arrivals.firstOrNull()
        val originalStatus = when {
            original == null || original.appVersion.isBlank() -> "ORIGINAL_VERSION_UNKNOWN"
            arrivals.map { it.appVersion }.distinct().size > 1 -> "AMBIGUOUS_SCREENING_VERSIONS"
            else -> "RECORDED_AT_SCREENING"
        }
        // Check the latest assessment. An older matching snapshot cannot establish the version
        // of the current row after a later, different assessment or an unaudited mutation.
        val latestAssessment = own.lastOrNull {
            it.eventType in assessmentTypes && DecisionTrace.details(it).let { d ->
                d.outcome != null && d.score != null
            }
        }
        val current = latestAssessment?.takeIf { matches(row, DecisionTrace.details(it)) && it.appVersion.isNotBlank() }
        return CallVersionProvenance(
            originalVersionStatus = originalStatus,
            originalDecision = original?.takeIf { originalStatus == "RECORDED_AT_SCREENING" }?.let(::assessment),
            currentClassificationStatus = if (current != null) "MATCHED_CLASSIFICATION_SNAPSHOT" else "CURRENT_CLASSIFICATION_VERSION_UNKNOWN",
            currentClassification = current?.let(::assessment),
            eventAppVersions = own.map(::version).distinct().sortedWith(compareBy({ it.versionCode ?: -1 }, { it.label }))
        )
    }

    private fun assessment(event: CallDecisionEventEntity) = ClassificationProvenance(
        event.eventId, event.eventType, event.source, event.recordedAt, version(event),
        DecisionTrace.details(event).engineRevision
    )

    private fun matches(row: CallTelemetryEntity, details: DecisionDetails): Boolean {
        val order = compareBy<br.com.spamshield.data.local.entity.TriggeredRuleDto>({ it.rule_id }, { it.weight }, { it.meta })
        return details.score == row.finalScore && details.outcome == row.outcome &&
            details.threshold == row.thresholdApplied && details.category == row.category &&
            details.prefix == row.prefix7 &&
            details.rules.sortedWith(order) == UserFeedbackAudit.decode(row.triggeredRulesJson).sortedWith(order)
    }
}
