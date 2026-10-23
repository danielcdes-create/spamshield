package br.com.spamshield.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class TriggeredRuleDto(
    val rule_id: String,
    val weight: Int,
    val meta: String? = null
)

@Serializable
@Entity(
    tableName = "call_telemetry",
    indices = [
        Index(value = ["prefix7", "timestamp"]),
        Index(value = ["normalizedNumber", "timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class CallTelemetryEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val deviceId: String = "android-edge-node",
    val rawNumber: String,
    val normalizedNumber: String,
    val ddd: String,
    val prefix7: String,
    val carrierGuess: String?,
    val lineType: String, // MOBILE | FIXED_OR_VOIP | TOLL_FREE_OR_SPECIAL | UNKNOWN
    val stirShakenStatus: String, // PASSED | FAILED | NOT_VERIFIED
    val finalScore: Int,
    val thresholdApplied: Int,
    val outcome: String, // BLOCKED | SHADOW_BLOCKED | ALLOWED_CONTACT | ALLOWED_EMERGENCY_REPEAT | ALLOWED_OUTBOUND_GRACE | ALLOWED_LOW_SCORE
    val category: String, // ROBOCALL_PREFIX_CLUSTER | SPOOFING_STIR_FAILED | KNOWN_BLACKLIST | PING_CALL_ROBOCALL | LEGITIMATE | UNKNOWN_UNVERIFIED
    val processingLatencyMs: Double,
    val shadowModeActive: Boolean,
    val callDurationSec: Int? = null,
    val triggeredRulesJson: String, // JSON array of TriggeredRuleDto
    val userFeedback: String = "UNRATED", // UNRATED | CONFIRMED_SPAM | FALSE_POSITIVE
    val syncStatus: String = "PENDING" // PENDING | SYNCED
)

@Serializable
@Entity(tableName = "active_rules")
data class ActiveRuleEntity(
    @PrimaryKey
    val ruleId: String,
    val name: String,
    val ruleType: String, // SHORT_CIRCUIT_ALLOW | HEURISTIC_WEIGHT | REGEX_MATCH
    val pattern: String?,
    val weight: Int,
    val priority: Int,
    val enabled: Boolean,
    val updatedAt: Long
)

@Serializable
@Entity(tableName = "prefix_quarantine")
data class PrefixQuarantineEntity(
    @PrimaryKey
    val prefix7: String,
    val ddd: String,
    val carrierGuess: String?,
    val distinctNumbersJson: String = "[]",
    val hitCount: Int,
    val penaltyWeight: Int,
    val reason: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val expiresAt: Long
)

@Entity(
    tableName = "outbound_grace",
    indices = [
        Index(value = ["prefix6", "expiresAt"])
    ]
)
data class OutboundGraceEntity(
    @PrimaryKey
    val normalizedNumber: String,
    val prefix6: String,
    val expiresAt: Long
)

@Entity(
    tableName = "anatel_numbering_plan",
    indices = [
        Index(value = ["ddd", "lineType"])
    ]
)
data class AnatelPrefixEntity(
    @PrimaryKey
    val prefix7: String,
    val ddd: String,
    val carrier: String,
    val lineType: String,
    val isCorporatePabxRange: Boolean,
    val updatedAt: Long
)
