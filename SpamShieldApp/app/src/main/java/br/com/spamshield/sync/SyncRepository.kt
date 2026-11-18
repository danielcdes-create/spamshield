package br.com.spamshield.sync

import android.util.Log
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.SpamShieldSettings
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.domain.TelemetrySyncScheduler
import br.com.spamshield.domain.UserFeedbackAudit
import br.com.spamshield.domain.DecisionTrace
import br.com.spamshield.data.remote.toRemoteEvent
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.domain.QuarantinePrefixIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
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
import java.security.MessageDigest
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
    private val syncMutex = Mutex()
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

    private fun buildApi(baseUrl: String, apiBearerToken: String): SpamShieldApi {
        TailscaleCleartext.requireAllowed(baseUrl)
        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (apiBearerToken.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiBearerToken")
                }
                chain.proceed(builder.build())
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(cleanUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpamShieldApi::class.java)
    }

    suspend fun checkServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = buildApi(baseUrl, "").getHealth()
            resp.status == "ok" || resp.mongodb_connected
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchRemoteApkInfo(): ApkInfoResponseDto? = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.getSnapshot()
            buildApi(settings.tailscaleServerUrl, settings.apiBearerToken).getApkInfo()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check remote APK info: ${e.message}")
            null
        }
    }

    suspend fun triggerProvidersSync(): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.getSnapshot()
            if (!settings.remoteSyncEnabled) return@withContext disabledSync()
            val api = buildApi(settings.tailscaleServerUrl, settings.apiBearerToken)
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
            TailscaleCleartext.requireAllowed(apkUrl)
            val info = buildApi(settings.tailscaleServerUrl, settings.apiBearerToken).getApkInfo()
            val reqBuilder = Request.Builder().url(apkUrl).get()
            if (settings.apiBearerToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${settings.apiBearerToken}")
            }
            val req = reqBuilder.build()
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
                val expected = info.sha256_checksum.trim().lowercase()
                val actual = sha256Hex(outFile.readBytes())
                if (expected.isEmpty() || expected != actual) {
                    outFile.delete()
                    Log.w(TAG, "Refusing APK install: checksum mismatch")
                    return@withContext null
                }
                outFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK update", e)
            null
        }
    }

    suspend fun performFullSync(): SyncOutcome = syncMutex.withLock { performFullSyncLocked() }

    private suspend fun performFullSyncLocked(): SyncOutcome = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSnapshot()
        if (!settings.remoteSyncEnabled) return@withContext disabledSync()
        val api = buildApi(settings.tailscaleServerUrl, settings.apiBearerToken)

        try {
            // 1. Push pending telemetry batches in chunks of 100
            var pushedCount = 0
            while (true) {
                if (!syncStillAllowed(settings)) return@withContext disabledSync()
                val pending = dao.getPendingTelemetry().take(100)
                if (pending.isEmpty()) break
                val events = pending.associate { it.id to dao.getDecisionEvents(it.id) }
                val batchDtos = pending.map { it.toRemoteDto(events[it.id].orEmpty()) }
                val batchResp = api.postTelemetryBatch(batchDtos)
                if (batchResp.accepted_ids.isNotEmpty()) {
                    dao.markTelemetrySynced(batchResp.accepted_ids)
                    // Acknowledge only the snapshot actually sent; concurrent events stay pending.
                    val sentEvents = batchResp.accepted_ids.flatMap { events[it].orEmpty() }
                    val acknowledged = sentEvents.map { it.eventId }.intersect(batchResp.accepted_event_ids.toSet())
                    dao.markDecisionEventsSynced(acknowledged.toList())
                    if (sentEvents.any { it.syncStatus == "PENDING" && it.eventId !in acknowledged }) {
                        return@withContext SyncOutcome(success = false, pushedTelemetryCount = pushedCount,
                            message = "Eventos preservados no aparelho: o servidor ainda não confirmou o diário de decisões.")
                    }
                    pushedCount += batchResp.accepted_ids.size
                } else {
                    break
                }
            }

            // Only durable local mutations are sent; downloaded snapshots are never echoed.
            if (!flushMutationOutbox(api, settings)) return@withContext disabledSync()
            val now = System.currentTimeMillis()

            // 2. Pull updated rules, quarantines, and Anatel prefixes
            if (!syncStillAllowed(settings)) return@withContext disabledSync()
            val payload = api.getSyncPayload(
                ddd = settings.userDdd,
                sinceTimestamp = settings.lastSyncTimestamp
            )

            dao.deduplicateHistoryRecords()
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
                dao.applyRemoteRules(localRules)
            }

            val remoteQuarantines = payload.prefix_quarantine.map { q ->
                PrefixQuarantineEntity(q.prefix_7, q.ddd, q.carrier_guess,
                    json.encodeToString(q.distinct_numbers_seen), q.hit_count, q.penalty_weight,
                    q.reason, parseIsoToMillis(q.first_seen_at, now),
                    parseIsoToMillis(q.last_seen_at, now), parseIsoToMillis(q.expires_at, now))
            }
            if (payload.quarantine_snapshot_complete) {
                dao.applyRemoteQuarantines(remoteQuarantines, payload.quarantine_deleted_prefixes)
            } else if (remoteQuarantines.isNotEmpty()) {
                // Old servers have no deletion contract: merge without losing local operations.
                dao.applyRemoteQuarantines(remoteQuarantines)
            }
            QuarantinePrefixIndex.refreshActive { dao.getAllQuarantines() }

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
            val current = dao.getTelemetryById(telemetryId) ?: return@withContext
            val mutationId = UUID.randomUUID().toString()
            val recordedAt = System.currentTimeMillis()
            val mutation = SyncMutationEntity(mutationId, "FEEDBACK", telemetryId,
                json.encodeToString(FeedbackPatchRequestDto(feedback, mutationId, recordedAt)), recordedAt)
            val updatedRow = when (feedback) {
                "CONFIRMED_SPAM" -> UserFeedbackAudit.applyConfirmedSpam(current)
                "FALSE_POSITIVE" -> {
                    val rules = UserFeedbackAudit.decode(current.triggeredRulesJson)
                        .filter { it.rule_id !in setOf("USER_CONFIRMED_SPAM_OVERRIDE", "USER_FALSE_POSITIVE_OVERRIDE") } +
                        TriggeredRuleDto("USER_FALSE_POSITIVE_OVERRIDE", -1000, "Feedback manual do utilizador")
                    current.copy(userFeedback = feedback, outcome = "ALLOWED_CONTACT", category = "LEGITIMATE",
                        finalScore = maxOf(0, rules.sumOf { it.weight }), triggeredRulesJson = json.encodeToString(rules), syncStatus = "PENDING")
                }
                else -> current.copy(userFeedback = feedback, syncStatus = "PENDING")
            }
            dao.persistFeedbackUpdate(updatedRow, mutation)
            if (feedback == "FALSE_POSITIVE" && prefix7.isNotEmpty()) {
                deleteManualQuarantine(prefix7)
                if (prefix7.length == 8) {
                    deleteManualQuarantine(prefix7.take(7))
                }
            } else if (feedback == "CONFIRMED_SPAM" && prefix7.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val existing = dao.getActiveQuarantineForPrefix(prefix7, now)
                val qEntity = PrefixQuarantineEntity(
                    prefix7 = prefix7,
                    ddd = prefix7.take(2),
                    carrierGuess = existing?.carrierGuess ?: "Confirmado pelo Usuário",
                    distinctNumbersJson = existing?.distinctNumbersJson ?: "[]",
                    hitCount = (existing?.hitCount ?: 1) + 1,
                    penaltyWeight = 85,
                    reason = "CONFIRMED_SPAM",
                    firstSeenAt = existing?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    expiresAt = now + 30L * 24 * 60 * 60 * 1000L
                )
                saveManualQuarantine(qEntity)
            }

            dao.getTelemetryById(telemetryId)?.let { updated ->
                DecisionTrace.record(dao, telemetryId, "USER_FEEDBACK", "USER",
                    DecisionTrace.snapshot(updated).copy(reason = feedback))
                if (prefix7.isNotEmpty() && feedback in setOf("CONFIRMED_SPAM", "FALSE_POSITIVE")) {
                    val protection = dao.getActiveQuarantineForPrefix(prefix7, System.currentTimeMillis())
                    DecisionTrace.record(dao, telemetryId, "QUARANTINE_UPDATED", "USER",
                        br.com.spamshield.domain.DecisionDetails(prefix = prefix7,
                            reason = if (feedback == "FALSE_POSITIVE") "REMOVED_AFTER_FALSE_POSITIVE"
                            else "${protection?.reason}; expiresAt=${protection?.expiresAt}"))
                }
                TelemetrySyncScheduler.enqueueImmediateSync()
            }

            QuarantinePrefixIndex.refreshActive { dao.getAllQuarantines() }
            TelemetrySyncScheduler.enqueueImmediateSync()
        }

    suspend fun saveManualQuarantine(item: PrefixQuarantineEntity) = withContext(Dispatchers.IO) {
        dao.upsertQuarantine(item)
        QuarantinePrefixIndex.add(item.prefix7, item.expiresAt)
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun deleteManualQuarantine(prefix7: String) = withContext(Dispatchers.IO) {
        dao.deleteQuarantineByPrefix(prefix7)
        QuarantinePrefixIndex.remove(prefix7)
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    suspend fun saveCustomRule(rule: ActiveRuleEntity) = withContext(Dispatchers.IO) {
        dao.persistRuleUpdate(rule, SyncMutationEntity(UUID.randomUUID().toString(), "RULE_UPSERT", rule.ruleId,
            json.encodeToString(rule),
            System.currentTimeMillis()))
        TelemetrySyncScheduler.enqueueImmediateSync()
    }

    private fun disabledSync() = SyncOutcome(success = true,
        message = "Sincronização remota desativada. Dados e alterações permanecem no aparelho.")

    private fun PrefixQuarantineEntity.toRemoteQuarantine() = PrefixQuarantineRemoteDto(
        prefix7, ddd, carrierGuess,
        runCatching { json.decodeFromString<List<String>>(distinctNumbersJson) }.getOrDefault(emptyList()),
        hitCount, penaltyWeight, reason, Instant.ofEpochMilli(firstSeenAt).toString(),
        Instant.ofEpochMilli(lastSeenAt).toString(), Instant.ofEpochMilli(expiresAt).toString())

    private suspend fun syncStillAllowed(expected: SpamShieldSettings): Boolean {
        val current = settingsDataStore.getSnapshot()
        return current.remoteSyncEnabled && current.tailscaleServerUrl.trimEnd('/') == expected.tailscaleServerUrl.trimEnd('/') &&
            current.apiBearerToken == expected.apiBearerToken
    }

    private suspend fun flushMutationOutbox(api: SpamShieldApi, expected: SpamShieldSettings): Boolean {
        while (true) {
            val mutation = dao.getSyncMutations().firstOrNull() ?: return true
            if (!syncStillAllowed(expected)) return false
            when (mutation.kind) {
                "QUARANTINE_UPSERT" -> {
                    val evidence = json.decodeFromString<PrefixQuarantineEntity>(mutation.payloadJson)
                    api.upsertQuarantine(evidence.toRemoteQuarantine(), evidence.lastSeenAt)
                }
                "QUARANTINE_DELETE" -> api.deleteQuarantine(mutation.targetId, mutation.createdAt)
                "FEEDBACK" -> api.patchTelemetryFeedback(mutation.targetId,
                    json.decodeFromString<FeedbackPatchRequestDto>(mutation.payloadJson))
                "RULE_UPSERT" -> {
                    val rule = json.decodeFromString<ActiveRuleEntity>(mutation.payloadJson)
                    api.upsertActiveRule(ActiveRuleRemoteDto(rule.ruleId, rule.name, rule.ruleType, rule.pattern,
                        rule.weight, rule.priority, rule.enabled, Instant.ofEpochMilli(rule.updatedAt).toString()))
                }
                else -> error("Operação local desconhecida: ${mutation.kind}")
            }
            // Remove only this immutable operation. A concurrent newer change stays queued.
            dao.acknowledgeSyncMutation(mutation.mutationId)
        }
    }

    internal fun CallTelemetryEntity.toRemoteDto(events: List<CallDecisionEventEntity>): CallTelemetryRemoteDto {
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
                stir_shaken_status = stirShakenStatus,
                call_direction = if (outcome == "OUTGOING_CALL") "OUTGOING" else null
            ),
            decision_vector = DecisionVectorRemoteDto(
                final_score = finalScore,
                threshold_applied = thresholdApplied,
                // Older servers validate the original enum; direction and journal retain the fact.
                outcome = if (outcome == "OUTGOING_CALL") "ALLOWED_LOW_SCORE" else outcome,
                category = if (outcome == "OUTGOING_CALL") "LEGITIMATE" else category,
                processing_latency_ms = processingLatencyMs,
                shadow_mode_active = shadowModeActive,
                call_duration_sec = callDurationSec,
                triggered_rules = rules
            ),
            decision_events = events.map { it.toRemoteEvent() },
            audit = AuditRemoteDto(
                user_feedback = userFeedback,
                synced_at = System.currentTimeMillis()
            )
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
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
