package br.com.spamshield.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.AnatelPrefixEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpamShieldDao {

    // ========================================================================
    // 1. Call Telemetry
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(telemetry: CallTelemetryEntity)

    @Query("SELECT * FROM call_telemetry ORDER BY timestamp DESC")
    fun observeAllTelemetry(): Flow<List<CallTelemetryEntity>>

    @Query("SELECT * FROM call_telemetry ORDER BY timestamp DESC")
    suspend fun getAllTelemetry(): List<CallTelemetryEntity>

    @Query("SELECT * FROM call_telemetry WHERE id = :id LIMIT 1")
    suspend fun getTelemetryById(id: String): CallTelemetryEntity?

    @Query("SELECT * FROM call_telemetry WHERE syncStatus = 'PENDING' ORDER BY timestamp ASC LIMIT 200")
    suspend fun getPendingTelemetry(): List<CallTelemetryEntity>

    @Query("UPDATE call_telemetry SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markTelemetrySynced(ids: List<String>)

    @Query(
        """
        UPDATE call_telemetry 
        SET userFeedback = :feedback,
            outcome = CASE 
                WHEN :feedback = 'CONFIRMED_SPAM' THEN 'BLOCKED'
                WHEN :feedback = 'FALSE_POSITIVE' THEN 'ALLOWED_CONTACT'
                ELSE outcome
            END,
            category = CASE 
                WHEN :feedback = 'CONFIRMED_SPAM' THEN 'ROBOCALL_PREFIX_CLUSTER'
                WHEN :feedback = 'FALSE_POSITIVE' THEN 'LEGITIMATE'
                ELSE category
            END,
            finalScore = CASE 
                WHEN :feedback = 'CONFIRMED_SPAM' AND finalScore < 100 THEN 100
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuarantines(items: List<PrefixQuarantineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuarantine(item: PrefixQuarantineEntity)

    @Query("DELETE FROM prefix_quarantine WHERE prefix7 = :prefix7")
    suspend fun deleteQuarantineByPrefix(prefix7: String)

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
        WHERE (normalizedNumber = :normalizedNumber OR prefix6 = :prefix6) 
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
