package br.com.spamshield.data.remote

import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class CallDetailsRemoteDto(
    val raw_number: String,
    val normalized_number: String,
    val ddd: String,
    val prefix_7: String,
    val carrier_guess: String? = null,
    val line_type: String,
    val stir_shaken_status: String
)

@Serializable
data class DecisionVectorRemoteDto(
    val final_score: Int,
    val threshold_applied: Int,
    val outcome: String,
    val category: String,
    val processing_latency_ms: Double,
    val shadow_mode_active: Boolean = false,
    val call_duration_sec: Int? = null,
    val triggered_rules: List<TriggeredRuleDto>
)

@Serializable
data class AuditRemoteDto(
    val user_feedback: String,
    val synced_at: Long = System.currentTimeMillis()
)

@Serializable
data class CallTelemetryRemoteDto(
    @SerialName("_id")
    val id: String,
    val timestamp: Long,
    val device_id: String,
    val call_details: CallDetailsRemoteDto,
    val decision_vector: DecisionVectorRemoteDto,
    val audit: AuditRemoteDto
)

@Serializable
data class BatchTelemetryResponseDto(
    val accepted_ids: List<String> = emptyList(),
    val quarantine_updates: Int = 0
)

@Serializable
data class ActiveRuleRemoteDto(
    val rule_id: String,
    val name: String,
    val rule_type: String,
    val pattern: String? = null,
    val weight: Int,
    val priority: Int,
    val enabled: Boolean,
    val updated_at: String = ""
)

@Serializable
data class PrefixQuarantineRemoteDto(
    val prefix_7: String,
    val ddd: String,
    val carrier_guess: String? = null,
    val distinct_numbers_seen: List<String> = emptyList(),
    val hit_count: Int = 1,
    val penalty_weight: Int = 60,
    val reason: String = "ROBOCALL_PREFIX_CLUSTER",
    val first_seen_at: String = "",
    val last_seen_at: String = "",
    val expires_at: String = ""
)

@Serializable
data class AnatelPlanRemoteDto(
    val prefix_7: String,
    val ddd: String,
    val carrier: String,
    val line_type: String,
    val is_corporate_pabx_range: Boolean = false,
    val updated_at: String = ""
)

@Serializable
data class SyncPayloadResponseDto(
    val active_rules: List<ActiveRuleRemoteDto> = emptyList(),
    val prefix_quarantine: List<PrefixQuarantineRemoteDto> = emptyList(),
    val anatel_numbering_plan: List<AnatelPlanRemoteDto> = emptyList(),
    val server_timestamp: Long = 0L
)

@Serializable
data class FeedbackPatchRequestDto(
    val user_feedback: String
)

@Serializable
data class FeedbackPatchResponseDto(
    val id: String,
    val user_feedback: String,
    val quarantine_removed_prefix: String? = null,
    val recalibrated_rules: List<String> = emptyList()
)

@Serializable
data class HealthResponseDto(
    val status: String = "ok",
    val service: String = "spamshield-server",
    val mongodb_connected: Boolean = true,
    val tailscale_ip: String = "100.83.253.15"
)

@Serializable
data class ApkInfoResponseDto(
    val version_code: Int = 1,
    val version_name: String = "1.0.0",
    val size_bytes: Long = 0L,
    val sha256_checksum: String = "",
    val download_path: String = "/apk"
)

@Serializable
data class ProviderSyncSummaryDto(
    val anatel_prefixes_synced: Int = 0,
    val blacklist_clusters_synced: Int = 0,
    val rules_synced: Int = 0,
    val sources: List<String> = emptyList()
)

@Serializable
data class QuarantineBatchResponseDto(
    val upserted: Int = 0,
    val received: Int = 0
)

interface SpamShieldApi {

    @GET("/api/v1/health")
    suspend fun getHealth(): HealthResponseDto

    @GET("/api/v1/apk/info")
    suspend fun getApkInfo(): ApkInfoResponseDto

    @POST("/api/v1/providers/sync")
    suspend fun syncProviders(): ProviderSyncSummaryDto

    @POST("/api/v1/telemetry/batch")
    suspend fun postTelemetryBatch(
        @Body batch: List<CallTelemetryRemoteDto>
    ): BatchTelemetryResponseDto

    @GET("/api/v1/sync/payload")
    suspend fun getSyncPayload(
        @Query("ddd") ddd: String,
        @Query("since_timestamp") sinceTimestamp: Long
    ): SyncPayloadResponseDto

    @PATCH("/api/v1/telemetry/{id}/feedback")
    suspend fun patchTelemetryFeedback(
        @Path("id") id: String,
        @Body body: FeedbackPatchRequestDto
    ): FeedbackPatchResponseDto

    @POST("/api/v1/rules")
    suspend fun upsertActiveRule(
        @Body rule: ActiveRuleRemoteDto
    ): ActiveRuleRemoteDto

    @POST("/api/v1/quarantine")
    suspend fun upsertQuarantine(
        @Body quarantine: PrefixQuarantineRemoteDto
    ): PrefixQuarantineRemoteDto

    @POST("/api/v1/quarantine/batch")
    suspend fun upsertQuarantineBatch(
        @Body quarantines: List<PrefixQuarantineRemoteDto>
    ): QuarantineBatchResponseDto

    @DELETE("/api/v1/quarantine/{prefix7}")
    suspend fun deleteQuarantine(
        @Path("prefix7") prefix7: String
    )
}
