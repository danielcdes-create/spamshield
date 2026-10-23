package br.com.spamshield.sync

import android.util.Log
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.data.remote.ActiveRuleRemoteDto
import br.com.spamshield.data.remote.ApkInfoResponseDto
import br.com.spamshield.data.remote.AuditRemoteDto
import br.com.spamshield.data.remote.CallDetailsRemoteDto
import br.com.spamshield.data.remote.CallTelemetryRemoteDto
import br.com.spamshield.data.remote.DecisionVectorRemoteDto
import br.com.spamshield.data.remote.FeedbackPatchRequestDto
import br.com.spamshield.data.remote.PrefixQuarantineRemoteDto
import br.com.spamshield.data.remote.SpamShieldApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncOutcome(
    val success: Boolean,
    val pushedTelemetryCount: Int = 0,
    val pulledRulesCount: Int = 0,
    val pulledQuarantineCount: Int = 0,
    val message: String = ""
)

@Singleton
class SyncRepository @Inject constructor(
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "TailscaleSync"
    }

    private fun buildApi(baseUrl: String): SpamShieldApi {
        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(cleanUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpamShieldApi::class.java)
    }

    suspend fun checkServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = buildApi(baseUrl).getHealth()
            resp.status == "ok" || resp.mongodb_connected
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchRemoteApkInfo(): ApkInfoResponseDto? = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.getSnapshot()
            buildApi(settings.tailscaleServerUrl).getApkInfo()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check remote APK info: ${e.message}")
            null
        }
    }

    suspend fun triggerProvidersSync(): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.getSnapshot()
            val api = buildApi(settings.tailscaleServerUrl)
            val summary = api.syncProviders()
            // Reset lastSyncTimestamp to 0 so all newly added Anatel prefixes are pulled
            settingsDataStore.setLastSyncTimestamp(0L)
            val full = performFullSync()
            full.copy(
                message = "Provedores Anatel/Blacklist sincronizados: ${summary.anatel_prefixes_synced} faixas Anatel, ${summary.blacklist_clusters_synced} clusters, ${summary.rules_synced} regras!"
            )
        } catch (e: Exception) {
            SyncOutcome(
                success = false,
                message = "Falha ao atualizar provedores Anatel: ${e.localizedMessage ?: "Erro"}"
            )
        }
    }

    suspend fun downloadApkUpdate(targetDir: File): File? = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.getSnapshot()
            val apkUrl = "${settings.tailscaleServerUrl.trimEnd('/')}/apk"
            val req = Request.Builder().url(apkUrl).get().build()
            val dlClient = okHttpClient.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            dlClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                targetDir.mkdirs()
                val outFile = File(targetDir, "spamshield-update.apk")
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                outFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK update", e)
            null
        }
    }

    suspend fun performFullSync(): SyncOutcome = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSnapshot()
        val api = buildApi(settings.tailscaleServerUrl)

        try {
            // 1. Push pending telemetry batches in chunks of 100
            var pushedCount = 0
            while (true) {
                val pending = dao.getPendingTelemetry().take(100)
                if (pending.isEmpty()) break
                val batchDtos = pending.map { it.toRemoteDto() }
                val batchResp = api.postTelemetryBatch(batchDtos)
                if (batchResp.accepted_ids.isNotEmpty()) {
                    dao.markTelemetrySynced(batchResp.accepted_ids)
                    pushedCount += batchResp.accepted_ids.size
                } else {
                    break
                }
            }

            // 2. Pull updated rules, quarantines, and Anatel prefixes
            val payload = api.getSyncPayload(
                ddd = settings.userDdd,
                sinceTimestamp = settings.lastSyncTimestamp
            )

            val now = System.currentTimeMillis()
            dao.purgeExpiredQuarantines(now)
            dao.purgeExpiredOutboundGrace(now)

            if (payload.active_rules.isNotEmpty()) {
                val localRules = payload.active_rules.map { r ->
                    ActiveRuleEntity(
                        ruleId = r.rule_id,
                        name = r.name,
                        ruleType = r.rule_type,
                        pattern = r.pattern,
                        weight = r.weight,
                        priority = r.priority,
                        enabled = r.enabled,
                        updatedAt = parseIsoToMillis(r.updated_at, now)
                    )
                }
                dao.insertRules(localRules)
            }

            if (payload.prefix_quarantine.isNotEmpty()) {
                val localQuarantines = payload.prefix_quarantine.map { q ->
                    PrefixQuarantineEntity(
                        prefix7 = q.prefix_7,
                        ddd = q.ddd,
                        carrierGuess = q.carrier_guess,
                        distinctNumbersJson = json.encodeToString(q.distinct_numbers_seen),
                        hitCount = q.hit_count,
                        penaltyWeight = q.penalty_weight,
                        reason = q.reason,
                        firstSeenAt = parseIsoToMillis(q.first_seen_at, now),
                        lastSeenAt = parseIsoToMillis(q.last_seen_at, now),
                        expiresAt = parseIsoToMillis(q.expires_at, now + 7L * 86_400_000L)
                    )
                }
                dao.insertQuarantines(localQuarantines)
            }

            if (payload.anatel_numbering_plan.isNotEmpty()) {
                val localAnatel = payload.anatel_numbering_plan.map { a ->
                    AnatelPrefixEntity(
                        prefix7 = a.prefix_7,
                        ddd = a.ddd,
                        carrier = a.carrier,
                        lineType = a.line_type,
                        isCorporatePabxRange = a.is_corporate_pabx_range,
                        updatedAt = parseIsoToMillis(a.updated_at, now)
                    )
                }
                dao.insertAnatelPrefixes(localAnatel)
            }

            val newSyncTs = if (payload.server_timestamp > 0) payload.server_timestamp else now
            settingsDataStore.setLastSyncTimestamp(newSyncTs)

            Log.d(
                TAG,
                "Tailscale sync complete: pushed=$pushedCount, " +
                    "rules=${payload.active_rules.size}, quarantines=${payload.prefix_quarantine.size}"
            )

            SyncOutcome(
                success = true,
                pushedTelemetryCount = pushedCount,
                pulledRulesCount = payload.active_rules.size,
                pulledQuarantineCount = payload.prefix_quarantine.size,
                message = "Sincronizado ($pushedCount envios, ${payload.prefix_quarantine.size} quarentenas)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tailscale sync failed: ${e.message}")
            SyncOutcome(
                success = false,
                message = "Falha na sincronização: ${e.localizedMessage ?: "Servidor inacessível"}"
            )
        }
    }

    suspend fun submitFeedback(telemetryId: String, feedback: String, prefix7: String) =
        withContext(Dispatchers.IO) {
            dao.updateUserFeedback(telemetryId, feedback)
            if (feedback == "FALSE_POSITIVE" && prefix7.isNotEmpty()) {
                dao.deleteQuarantineByPrefix(prefix7)
            } else if (feedback == "CONFIRMED_SPAM" && prefix7.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val existing = dao.getActiveQuarantineForPrefix(prefix7, now)
                dao.upsertQuarantine(
                    PrefixQuarantineEntity(
                        prefix7 = prefix7,
                        ddd = prefix7.take(2),
                        carrierGuess = existing?.carrierGuess ?: "Confirmado pelo Usuário",
                        distinctNumbersJson = existing?.distinctNumbersJson ?: "[]",
                        hitCount = (existing?.hitCount ?: 1) + 1,
                        penaltyWeight = 80,
                        reason = "CONFIRMED_SPAM",
                        firstSeenAt = existing?.firstSeenAt ?: now,
                        lastSeenAt = now,
                        expiresAt = now + 30L * 24 * 60 * 60 * 1000L
                    )
                )
            }

            try {
                val settings = settingsDataStore.getSnapshot()
                val api = buildApi(settings.tailscaleServerUrl)
                api.patchTelemetryFeedback(telemetryId, FeedbackPatchRequestDto(feedback))
                dao.markTelemetrySynced(listOf(telemetryId))
            } catch (e: Exception) {
                Log.w(TAG, "Deferred feedback sync for $telemetryId: ${e.message}")
            }
        }

    suspend fun saveManualQuarantine(item: PrefixQuarantineEntity) = withContext(Dispatchers.IO) {
        dao.upsertQuarantine(item)
        try {
            val settings = settingsDataStore.getSnapshot()
            val api = buildApi(settings.tailscaleServerUrl)
            val distinctNumbers = runCatching {
                json.decodeFromString<List<String>>(item.distinctNumbersJson)
            }.getOrDefault(emptyList())
            api.upsertQuarantine(
                PrefixQuarantineRemoteDto(
                    prefix_7 = item.prefix7,
                    ddd = item.ddd,
                    carrier_guess = item.carrierGuess,
                    distinct_numbers_seen = distinctNumbers,
                    hit_count = item.hitCount,
                    penalty_weight = item.penaltyWeight,
                    reason = item.reason,
                    first_seen_at = Instant.ofEpochMilli(item.firstSeenAt).toString(),
                    last_seen_at = Instant.ofEpochMilli(item.lastSeenAt).toString(),
                    expires_at = Instant.ofEpochMilli(item.expiresAt).toString()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Saved manual quarantine locally; remote sync deferred: ${e.message}")
        }
    }

    suspend fun deleteManualQuarantine(prefix7: String) = withContext(Dispatchers.IO) {
        dao.deleteQuarantineByPrefix(prefix7)
        try {
            val settings = settingsDataStore.getSnapshot()
            val api = buildApi(settings.tailscaleServerUrl)
            api.deleteQuarantine(prefix7)
        } catch (e: Exception) {
            Log.w(TAG, "Deleted quarantine locally; remote sync deferred: ${e.message}")
        }
    }

    suspend fun saveCustomRule(rule: ActiveRuleEntity) = withContext(Dispatchers.IO) {
        dao.upsertRule(rule)
        try {
            val settings = settingsDataStore.getSnapshot()
            val api = buildApi(settings.tailscaleServerUrl)
            api.upsertActiveRule(
                ActiveRuleRemoteDto(
                    rule_id = rule.ruleId,
                    name = rule.name,
                    rule_type = rule.ruleType,
                    pattern = rule.pattern,
                    weight = rule.weight,
                    priority = rule.priority,
                    enabled = rule.enabled,
                    updated_at = Instant.ofEpochMilli(rule.updatedAt).toString()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Saved rule locally; remote sync deferred: ${e.message}")
        }
    }

    private fun CallTelemetryEntity.toRemoteDto(): CallTelemetryRemoteDto {
        val rules = runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(triggeredRulesJson)
        }.getOrDefault(emptyList())

        return CallTelemetryRemoteDto(
            id = id,
            timestamp = timestamp,
            device_id = deviceId,
            call_details = CallDetailsRemoteDto(
                raw_number = rawNumber,
                normalized_number = normalizedNumber,
                ddd = ddd,
                prefix_7 = prefix7,
                carrier_guess = carrierGuess,
                line_type = lineType,
                stir_shaken_status = stirShakenStatus
            ),
            decision_vector = DecisionVectorRemoteDto(
                final_score = finalScore,
                threshold_applied = thresholdApplied,
                outcome = outcome,
                category = category,
                processing_latency_ms = processingLatencyMs,
                shadow_mode_active = shadowModeActive,
                call_duration_sec = callDurationSec,
                triggered_rules = rules
            ),
            audit = AuditRemoteDto(
                user_feedback = userFeedback,
                synced_at = System.currentTimeMillis()
            )
        )
    }

    private fun parseIsoToMillis(iso: String, fallback: Long): Long {
        if (iso.isBlank()) return fallback
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            iso.toLongOrNull() ?: fallback
        }
    }
}
