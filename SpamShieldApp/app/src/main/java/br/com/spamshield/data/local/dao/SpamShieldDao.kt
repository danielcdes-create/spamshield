package br.com.spamshield.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.data.local.entity.RemoteQuarantinePrefixEntity
import br.com.spamshield.domain.TelemetryCoalescer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Dao
interface SpamShieldDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueSyncMutation(mutation: SyncMutationEntity)

    @Query("SELECT * FROM sync_mutations ORDER BY rowid")
    suspend fun getSyncMutations(): List<SyncMutationEntity>

    @Query("DELETE FROM sync_mutations WHERE mutationId = :mutationId")
    suspend fun acknowledgeSyncMutation(mutationId: String)

    @Transaction
    suspend fun persistFeedbackUpdate(
        row: CallTelemetryEntity,
        mutation: SyncMutationEntity,
        event: CallDecisionEventEntity
    ) {
        insertTelemetry(row)
        insertDecisionEvent(event)
        enqueueSyncMutation(mutation)
    }

    @Transaction
    suspend fun persistRuleUpdate(rule: ActiveRuleEntity, mutation: SyncMutationEntity) {
        upsertRule(rule)
        enqueueSyncMutation(mutation)
    }

    @Transaction
    suspend fun applyRemoteRules(items: List<ActiveRuleEntity>) {
        insertRules(items)
        getSyncMutations().filter { it.kind == "RULE_UPSERT" }.forEach {
            upsertRule(Json.decodeFromString<ActiveRuleEntity>(it.payloadJson))
        }
    }

    @Query("SELECT prefix7 FROM remote_quarantine_prefixes")
    suspend fun getRemoteQuarantinePrefixes(): List<String>

    @Query("DELETE FROM remote_quarantine_prefixes")
    suspend fun clearRemoteQuarantinePrefixes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemoteQuarantinePrefixes(items: List<RemoteQuarantinePrefixEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDecisionEvent(event: CallDecisionEventEntity)

    @Query("SELECT * FROM call_decision_events ORDER BY recordedAt, eventId")
    suspend fun getAllDecisionEvents(): List<CallDecisionEventEntity>

    @Query("SELECT * FROM call_decision_events ORDER BY recordedAt, eventId")
    fun observeDecisionEvents(): Flow<List<CallDecisionEventEntity>>

    @Query("SELECT * FROM call_decision_events WHERE callId = :callId ORDER BY recordedAt, eventId")
    suspend fun getDecisionEvents(callId: String): List<CallDecisionEventEntity>

    @Query("SELECT * FROM call_decision_events WHERE callId IN (:callIds) ORDER BY recordedAt, eventId")
    suspend fun getDecisionEventsForCalls(callIds: List<String>): List<CallDecisionEventEntity>

    @Query("UPDATE call_decision_events SET syncStatus = 'SYNCED' WHERE callId = :callId AND eventId IN (:eventIds)")
    suspend fun markDecisionEventsSynced(callId: String, eventIds: List<String>)

    @Query("DELETE FROM call_decision_events WHERE eventId IN (:eventIds)")
    suspend fun deleteDecisionEvents(eventIds: List<String>)

    @Query("UPDATE call_decision_events SET callId = :toCallId, syncStatus = 'PENDING' WHERE callId = :fromCallId")
    suspend fun reassignDecisionEvents(fromCallId: String, toCallId: String)

    // ========================================================================
    // 1. Call Telemetry
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(telemetry: CallTelemetryEntity)

    @Transaction
    suspend fun persistScreeningAudit(
        row: CallTelemetryEntity,
        events: List<CallDecisionEventEntity>
    ): CallTelemetryEntity {
        val prior = getLatestTelemetryForNumber(row.normalizedNumber)
        val canonical = if (prior != null && prior.id != row.id && prior.timestamp == row.timestamp) {
            TelemetryCoalescer.merge(prior, row).copy(id = prior.id, syncStatus = "PENDING")
        } else {
            row
        }
        insertTelemetry(canonical)
        for (event in events) insertDecisionEvent(event.copy(callId = canonical.id))
        return canonical
    }

    @Query("DELETE FROM call_telemetry WHERE id = :id")
    suspend fun deleteTelemetryById(id: String)

    @Query("""
        DELETE FROM call_telemetry
        WHERE id = :id AND syncStatus = 'PENDING' AND userFeedback = 'UNRATED'
          AND NOT EXISTS (SELECT 1 FROM call_decision_events e WHERE e.callId = :id)
    """)
    suspend fun deleteUnjournaledPendingTelemetry(id: String): Int

    @Query("SELECT * FROM call_telemetry ORDER BY timestamp DESC")
    fun observeAllTelemetry(): Flow<List<CallTelemetryEntity>>

    @Query("SELECT * FROM call_telemetry ORDER BY timestamp DESC")
    suspend fun getAllTelemetry(): List<CallTelemetryEntity>

    @Transaction
    suspend fun getTelemetryExportSnapshot(): Pair<List<CallTelemetryEntity>, List<CallDecisionEventEntity>> =
        getAllTelemetry() to getAllDecisionEvents()

    @Query("SELECT * FROM call_telemetry WHERE id = :id LIMIT 1")
    suspend fun getTelemetryById(id: String): CallTelemetryEntity?

    @Query("""
        SELECT * FROM call_telemetry
        WHERE (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId))
          AND (syncStatus = 'PENDING' OR EXISTS (
              SELECT 1 FROM call_decision_events e WHERE e.callId = call_telemetry.id AND e.syncStatus = 'PENDING'
          ))
        ORDER BY timestamp, id
        LIMIT 100
    """)
    suspend fun getPendingTelemetry(
        afterTimestamp: Long = Long.MIN_VALUE,
        afterId: String = ""
    ): List<CallTelemetryEntity>

    @Transaction
    suspend fun getPendingTelemetrySnapshot(
        afterTimestamp: Long,
        afterId: String
    ): Pair<List<CallTelemetryEntity>, List<CallDecisionEventEntity>> {
        val rows = getPendingTelemetry(afterTimestamp, afterId)
        val events = if (rows.isEmpty()) emptyList() else getDecisionEventsForCalls(rows.map { it.id })
        return rows to events
    }

    @Query("UPDATE call_telemetry SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markTelemetrySynced(ids: List<String>)

    @Transaction
    suspend fun acknowledgeTelemetryUpload(row: CallTelemetryEntity, acknowledgedEventIds: List<String>) {
        if (getTelemetryById(row.id) == row) {
            markTelemetrySynced(listOf(row.id))
        }
        acknowledgedEventIds.chunked(500).forEach { ids -> markDecisionEventsSynced(row.id, ids) }
    }

    @Query(
        """
        UPDATE call_telemetry
        SET userFeedback = :feedback,
            outcome = CASE
                WHEN :feedback = 'FALSE_POSITIVE' THEN 'ALLOWED_CONTACT'
                ELSE outcome
            END,
            category = CASE
                WHEN :feedback = 'FALSE_POSITIVE' THEN 'LEGITIMATE'
                ELSE category
            END,
            finalScore = CASE
                WHEN :feedback = 'FALSE_POSITIVE' THEN 0
                ELSE finalScore
            END,
            syncStatus = 'PENDING'
        WHERE id = :id
        """
    )
    suspend fun updateUserFeedback(id: String, feedback: String)

    @Query(
        """
        SELECT * FROM call_telemetry
        WHERE normalizedNumber = :normalizedNumber
          AND deviceId != 's23-ultra-history'
          AND timestamp BETWEEN :minTs AND :maxTs
        ORDER BY ABS(timestamp - (:minTs + (:maxTs - :minTs) / 2)), timestamp
        LIMIT 1
        """
    )
    suspend fun findRealTimeTelemetryNearTimestamp(
        normalizedNumber: String,
        minTs: Long,
        maxTs: Long
    ): CallTelemetryEntity?

    @Query(
        """
        DELETE FROM call_telemetry
        WHERE deviceId = 's23-ultra-history'
          AND syncStatus = 'PENDING'
          AND userFeedback = 'UNRATED'
          AND NOT EXISTS (SELECT 1 FROM call_decision_events e WHERE e.callId = call_telemetry.id)
          AND EXISTS (
              SELECT 1 FROM call_telemetry rt
              WHERE rt.deviceId != 's23-ultra-history'
                AND rt.normalizedNumber = call_telemetry.normalizedNumber
                AND ABS(rt.timestamp - call_telemetry.timestamp) <= 15000
          )
        """
    )
    suspend fun deduplicateHistoryRecords()

    @Query(
        """
        UPDATE call_telemetry
        SET callDurationSec = :durationSec,
            category = :category,
            syncStatus = 'PENDING'
        WHERE id = :id
        """
    )
    suspend fun updateCallDurationAndCategory(id: String, durationSec: Int, category: String)

    @Query(
        """
        SELECT * FROM call_telemetry
        WHERE normalizedNumber = :normalizedNumber
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun getLatestTelemetryForNumber(normalizedNumber: String): CallTelemetryEntity?

    @Query(
        """
        SELECT COUNT(*) FROM call_telemetry
        WHERE normalizedNumber = :normalizedNumber
          AND timestamp >= :sinceTimestamp
        """
    )
    suspend fun countRecentCallsFromNumber(normalizedNumber: String, sinceTimestamp: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT normalizedNumber) FROM call_telemetry
        WHERE prefix7 = :prefix7
          AND timestamp >= :sinceTimestamp
        """
    )
    suspend fun countDistinctNumbersInPrefix7Since(prefix7: String, sinceTimestamp: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT normalizedNumber) FROM call_telemetry
        WHERE prefix7 LIKE :prefix6 || '%'
          AND timestamp >= :sinceTimestamp
        """
    )
    suspend fun countDistinctNumbersInPrefix6Since(prefix6: String, sinceTimestamp: Long): Int

    // ========================================================================
    // 2. Active Rules
    // ========================================================================

    @Query("SELECT * FROM active_rules WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledRules(): List<ActiveRuleEntity>

    @Query("SELECT * FROM active_rules ORDER BY priority ASC")
    fun observeAllRules(): Flow<List<ActiveRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<ActiveRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: ActiveRuleEntity)

    @Query("DELETE FROM active_rules WHERE ruleId = :ruleId")
    suspend fun deleteRule(ruleId: String)

    @Query("SELECT COUNT(*) FROM active_rules")
    suspend fun countRules(): Int

    // ========================================================================
    // 3. Prefix Quarantine
    // ========================================================================

    @Query(
        """
        SELECT * FROM prefix_quarantine
        WHERE (prefix7 = :prefix8 OR prefix7 = :prefix7)
          AND expiresAt > :now
        ORDER BY LENGTH(prefix7) DESC
        LIMIT 1
        """
    )
    suspend fun getActiveQuarantineForPrefix(prefix8: String, prefix7: String, now: Long): PrefixQuarantineEntity?

    @Query("SELECT * FROM prefix_quarantine WHERE prefix7 = :prefix7 AND expiresAt > :now LIMIT 1")
    suspend fun getActiveQuarantineForPrefix(prefix7: String, now: Long): PrefixQuarantineEntity?

    @Query("SELECT COUNT(*) FROM prefix_quarantine WHERE prefix7 LIKE :prefix6 || '%' AND expiresAt > :now")
    suspend fun countActiveQuarantinesByPrefix6(prefix6: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM prefix_quarantine WHERE prefix7 LIKE :prefix5 || '%' AND expiresAt > :now")
    suspend fun countActiveQuarantinesByPrefix5(prefix5: String, now: Long): Int

    @Query("SELECT * FROM prefix_quarantine WHERE expiresAt > :now ORDER BY hitCount DESC, lastSeenAt DESC")
    fun observeActiveQuarantines(now: Long): Flow<List<PrefixQuarantineEntity>>

    @Query("SELECT * FROM prefix_quarantine")
    suspend fun getAllQuarantines(): List<PrefixQuarantineEntity>

    @Query("DELETE FROM prefix_quarantine")
    suspend fun clearAllQuarantines()

    @Transaction
    suspend fun replaceAllQuarantines(items: List<PrefixQuarantineEntity>) {
        clearAllQuarantines()
        insertQuarantines(items)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuarantines(items: List<PrefixQuarantineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun persistQuarantine(item: PrefixQuarantineEntity)

    @Transaction
    suspend fun upsertQuarantine(item: PrefixQuarantineEntity) {
        persistQuarantine(item)
        enqueueSyncMutation(SyncMutationEntity(UUID.randomUUID().toString(), "QUARANTINE_UPSERT",
            item.prefix7, Json.encodeToString(item), System.currentTimeMillis()))
    }

    @Transaction
    suspend fun installAutomaticQuarantine(item: PrefixQuarantineEntity, now: Long): Boolean {
        val prefix = item.prefix7
        if (prefix.length !in 7..8 || !prefix.all { it.isDigit() } || item.expiresAt <= now) return false
        val broadPrefix = prefix.take(7)
        if (getActiveQuarantineForPrefix(prefix, broadPrefix, now) != null) return false
        if (getSyncMutations().any {
                it.kind == "QUARANTINE_DELETE" && (it.targetId == prefix || it.targetId == broadPrefix) &&
                    it.createdAt >= item.lastSeenAt
            }
        ) return false
        persistQuarantine(item)
        enqueueSyncMutation(SyncMutationEntity(UUID.randomUUID().toString(), "QUARANTINE_AUTO_UPSERT",
            prefix, Json.encodeToString(item), item.lastSeenAt))
        return true
    }

    @Query("DELETE FROM prefix_quarantine WHERE prefix7 = :prefix7")
    suspend fun persistQuarantineDeletion(prefix7: String)

    @Transaction
    suspend fun deleteQuarantineByPrefix(prefix7: String) {
        persistQuarantineDeletion(prefix7)
        enqueueSyncMutation(SyncMutationEntity(UUID.randomUUID().toString(), "QUARANTINE_DELETE",
            prefix7, "", System.currentTimeMillis()))
    }

    @Transaction
    suspend fun applyRemoteQuarantines(
        items: List<PrefixQuarantineEntity>,
        deletedPrefixes: List<String> = emptyList(),
        rejectedAutomatic: List<SyncMutationEntity> = emptyList()
    ) {
        for (rejected in rejectedAutomatic) {
            val item = Json.decodeFromString<PrefixQuarantineEntity>(rejected.payloadJson)
            if (getActiveQuarantineForPrefix(item.prefix7, Long.MIN_VALUE) == item) {
                persistQuarantineDeletion(item.prefix7)
            }
            acknowledgeSyncMutation(rejected.mutationId)
        }
        // A change arriving during the HTTP request must survive the remote snapshot.
        val pending = getSyncMutations()
        val received = items.map { it.prefix7 }.toSet()
        // Legacy prefixes have no origin information. Retain them until expiry or explicit deletion.
        for (prefix in getRemoteQuarantinePrefixes().filter { it !in received } + deletedPrefixes) {
            persistQuarantineDeletion(prefix)
        }
        items.forEach { persistQuarantine(it) }
        clearRemoteQuarantinePrefixes()
        insertRemoteQuarantinePrefixes(received.map { RemoteQuarantinePrefixEntity(it) })
        val now = System.currentTimeMillis()
        for (mutation in pending) {
            when (mutation.kind) {
                "QUARANTINE_UPSERT" -> persistQuarantine(Json.decodeFromString<PrefixQuarantineEntity>(mutation.payloadJson))
                "QUARANTINE_AUTO_UPSERT" -> {
                    val item = Json.decodeFromString<PrefixQuarantineEntity>(mutation.payloadJson)
                    val broad = item.prefix7.take(7)
                    val superseded = item.prefix7 in deletedPrefixes || broad in deletedPrefixes ||
                        (broad != item.prefix7 && getActiveQuarantineForPrefix(broad, now) != null)
                    if (superseded) {
                        if (item.prefix7 !in received && getActiveQuarantineForPrefix(item.prefix7, now) == item) {
                            persistQuarantineDeletion(item.prefix7)
                        }
                    } else if (item.expiresAt > now && getActiveQuarantineForPrefix(item.prefix7, now) == null) {
                        persistQuarantine(item)
                    }
                }
                "QUARANTINE_DELETE" -> persistQuarantineDeletion(mutation.targetId)
            }
        }
    }

    @Query("DELETE FROM prefix_quarantine WHERE expiresAt <= :now")
    suspend fun purgeExpiredQuarantines(now: Long)

    // ========================================================================
    // 4. Outbound Grace Period
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutboundGrace(entry: OutboundGraceEntity)

    @Query(
        """
        SELECT COUNT(*) FROM outbound_grace
        WHERE (normalizedNumber = :normalizedNumber OR (:prefix6 != '' AND prefix6 = :prefix6))
          AND expiresAt > :now
        """
    )
    suspend fun hasActiveOutboundGrace(
        normalizedNumber: String,
        prefix6: String,
        now: Long
    ): Int

    @Query("DELETE FROM outbound_grace WHERE expiresAt <= :now")
    suspend fun purgeExpiredOutboundGrace(now: Long)

    // ========================================================================
    // 5. Anatel Numbering Plan
    // ========================================================================

    @Query("SELECT * FROM anatel_numbering_plan WHERE prefix7 = :prefix7 LIMIT 1")
    suspend fun getAnatelPrefix(prefix7: String): AnatelPrefixEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnatelPrefixes(items: List<AnatelPrefixEntity>)
}
