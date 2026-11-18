package br.com.spamshield.sync

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.domain.FakeSpamShieldDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SyncConsentTest {
    @Test fun remoteSyncDefaultsOffAndLeavesOutboxPending() = runTest {
        val dao = FakeSpamShieldDao()
        val mutation = SyncMutationEntity("offline-delete", "QUARANTINE_DELETE", "31410111", "", 1000L)
        dao.enqueueSyncMutation(mutation)
        val settings = SettingsDataStore()
        assertFalse(settings.getSnapshot().remoteSyncEnabled)
        val result = SyncRepository(dao, settings).performFullSync()
        assertTrue(result.success)
        assertEquals(0, result.pushedTelemetryCount)
        assertEquals(listOf(mutation), dao.getSyncMutations())
    }

    @Test fun quarantineDeletionSurvivesRemoteSnapshotUntilAcknowledged() = runTest {
        val dao = FakeSpamShieldDao()
        val q = dao.quarantines.first()
        dao.quarantines.clear()
        dao.quarantines.add(q)
        dao.applyRemoteQuarantines(listOf(q))
        dao.deleteQuarantineByPrefix(q.prefix7)
        dao.applyRemoteQuarantines(listOf(q))
        assertTrue(dao.quarantines.none { it.prefix7 == q.prefix7 })
        assertEquals("QUARANTINE_DELETE", dao.getSyncMutations().single().kind)
        dao.acknowledgeSyncMutation(dao.getSyncMutations().single().mutationId)
        dao.applyRemoteQuarantines(emptyList())
        assertTrue(dao.quarantines.isEmpty())
    }

    @Test fun snapshotDoesNotEnqueueDownloadedQuarantinesButLocalChangeDoes() = runTest {
        val dao = FakeSpamShieldDao()
        val q = dao.quarantines.first()
        dao.applyRemoteQuarantines(listOf(q))
        assertTrue(dao.getSyncMutations().isEmpty())
        dao.upsertQuarantine(q.copy(hitCount = q.hitCount + 1))
        assertEquals("QUARANTINE_UPSERT", dao.getSyncMutations().single().kind)
    }

    @Test fun remoteDeletionDoesNotDiscardUnknownLegacyLearning() = runTest {
        val dao = FakeSpamShieldDao()
        val legacy = dao.quarantines[0]
        val downloaded = dao.quarantines[1]
        dao.applyRemoteQuarantines(listOf(downloaded))
        dao.applyRemoteQuarantines(emptyList())
        assertTrue(dao.quarantines.any { it.prefix7 == legacy.prefix7 })
        assertTrue(dao.quarantines.none { it.prefix7 == downloaded.prefix7 })
        dao.applyRemoteQuarantines(emptyList(), listOf(legacy.prefix7))
        assertTrue(dao.quarantines.none { it.prefix7 == legacy.prefix7 })
        assertTrue(dao.getSyncMutations().isEmpty())
    }

    @Test fun disabledRemoteRulesApplyAndPendingLocalEditsSurviveSnapshot() = runTest {
        val dao = FakeSpamShieldDao()
        val rule = dao.rules.first()
        val otherLocalRule = dao.rules.last()
        dao.applyRemoteRules(listOf(rule.copy(enabled = false)))
        assertFalse(dao.rules.single { it.ruleId == rule.ruleId }.enabled)
        assertTrue(dao.rules.any { it.ruleId == otherLocalRule.ruleId })
        val edited = rule.copy(enabled = true, weight = 123)
        dao.persistRuleUpdate(edited, SyncMutationEntity("rule-edit", "RULE_UPSERT", rule.ruleId,
            Json.encodeToString(edited), 1000L))
        dao.applyRemoteRules(listOf(rule.copy(enabled = false)))
        assertEquals(123, dao.rules.single { it.ruleId == rule.ruleId }.weight)
    }

    @Test fun outgoingWireStaysCompatibleAndRetainsDirectionAndOriginalJournal() {
        val row = CallTelemetryEntity(id = "outgoing-test", timestamp = 1000L,
            rawNumber = "10315", normalizedNumber = "10315", ddd = "", prefix7 = "",
            carrierGuess = null, lineType = "TOLL_FREE_OR_SPECIAL", stirShakenStatus = "NOT_VERIFIED",
            finalScore = 0, thresholdApplied = 70, outcome = "OUTGOING_CALL", category = "LEGITIMATE",
            processingLatencyMs = 0.0, shadowModeActive = false, triggeredRulesJson = "[]")
        val original = CallDecisionEventEntity("outgoing-event", row.id, 2000L, "HISTORY_ASSESSMENT",
            "CALL_LOG_IMPORT", "test", """{"outcome":"OUTGOING_CALL","systemCallType":2}""")
        val repository = SyncRepository(FakeSpamShieldDao(), SettingsDataStore())
        val wire = with(repository) { row.toRemoteDto(listOf(original)) }
        assertEquals("ALLOWED_LOW_SCORE", wire.decision_vector.outcome)
        assertEquals("LEGITIMATE", wire.decision_vector.category)
        assertEquals("OUTGOING", wire.call_details.call_direction)
        assertEquals("OUTGOING_CALL", wire.decision_events.single().details.outcome)
        assertEquals(2, wire.decision_events.single().details.systemCallType)
        assertEquals("OUTGOING_CALL", row.outcome)
    }
}
