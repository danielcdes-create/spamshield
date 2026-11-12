package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.remote.CallTelemetryRemoteDto
import br.com.spamshield.data.remote.toRemoteEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DecisionTraceTest {
    private val at = 1_794_400_000_000L
    private val raw = "031995201234"
    private fun dao() = FakeSpamShieldDao().apply { rules.clear(); quarantines.clear(); anatelPrefixes.clear() }
    private fun engine(dao: FakeSpamShieldDao) = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())

    @Before fun reset() { QuarantinePrefixIndex.clear() }
    @After fun cleanup() { QuarantinePrefixIndex.clear() }

    @Test fun manualRejectionNeverBecomesEvidenceOfPreventiveBlocking() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val arrival = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        DecisionTrace.record(dao, arrival.id, "SCREENING_RESPONSE_SUBMITTED", "CALL_SCREENING_SERVICE",
            DecisionDetails(disallowCall = false, rejectCall = false), at + 1)
        val original = dao.getDecisionEvents(arrival.id).single { it.eventType == "SCREENING_DECISION" }
        engine.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0,
            callTimestampMs = at, nowMillis = at + 10_000, systemBlockReason = 0)
        val current = dao.getTelemetryById(arrival.id)!!
        assertEquals("BLOCKED", current.outcome) // Legacy derived classification is not the actual action.
        val evidence = DecisionTrace.evidence(current, dao.getDecisionEvents(current.id))
        assertEquals("Rejeitada no discador", evidence.label)
        assertTrue(evidence.dialerRejected)
        assertFalse(evidence.blockRequested)
        assertFalse(evidence.blockObserved)
        assertEquals(45, evidence.arrivalScore)
        reprocessStored(dao)
        assertEquals(original, dao.getDecisionEvents(arrival.id).single { it.eventType == "SCREENING_DECISION" })
    }

    private suspend fun reprocessStored(dao: FakeSpamShieldDao) =
        br.com.spamshield.service.reprocessStoredTelemetry(dao, SettingsDataStore())

    @Test fun blockRequestNeedsAndroidObservationAndMatchingReason() = runTest {
        val dao = dao()
        QuarantinePrefixIndex.add("31995201")
        val row = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        DecisionTrace.record(dao, row.id, "SCREENING_RESPONSE_SUBMITTED", "CALL_SCREENING_SERVICE",
            DecisionDetails(disallowCall = true, rejectCall = true), at + 1)
        var evidence = DecisionTrace.evidence(row, dao.getDecisionEvents(row.id))
        assertTrue(evidence.blockRequested)
        assertFalse(evidence.blockObserved)
        DecisionTrace.record(dao, row.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = 6, systemBlockReason = 3), at + 2)
        assertFalse(DecisionTrace.evidence(row, dao.getDecisionEvents(row.id)).blockObserved)
        DecisionTrace.record(dao, row.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = 6, systemBlockReason = 1), at + 3)
        assertTrue(DecisionTrace.evidence(row, dao.getDecisionEvents(row.id)).blockObserved)
        DecisionTrace.record(dao, row.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = 6, systemBlockReason = 1,
                systemScreeningComponent = "other.app/Screening", systemScreeningAppIsSpamShield = false), at + 3_001)
        assertFalse(DecisionTrace.evidence(row, dao.getDecisionEvents(row.id)).blockObserved)
        DecisionTrace.record(dao, row.id, "CALL_LOG_OBSERVATION", "ANDROID_CALL_LOG",
            DecisionDetails(systemCallType = 5, systemBlockReason = 0), at + 4_000)
        evidence = DecisionTrace.evidence(row, dao.getDecisionEvents(row.id))
        assertFalse(evidence.blockObserved)
        assertTrue(evidence.dialerRejected)
    }

    @Test fun simulationAndLegacyScoresDoNotCountAsNativeBlocks() = runTest {
        val dao = dao()
        val row = engine(dao).evaluateIncomingCall(raw, "FAILED", at).telemetry
        assertEquals("BLOCKED", row.outcome)
        val legacy = DecisionTrace.evidence(row, emptyList())
        assertFalse(legacy.blockObserved)
        assertNull(legacy.arrivalScore)
        DecisionTrace.record(dao, row.id, "SCREENING_RESPONSE_SUBMITTED", "SIMULATION", DecisionDetails(disallowCall = true))
        assertFalse(DecisionTrace.evidence(row, dao.getDecisionEvents(row.id)).blockRequested)
    }

    @Test fun repeatedObservationIsIdempotentAndPreservesAllChangedResults() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val row = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        repeat(2) { engine.evaluatePostCallLogVerdict(row.normalizedNumber, 3, 0, callTimestampMs = at, nowMillis = at + 1000) }
        assertEquals(1, dao.getDecisionEvents(row.id).count { it.eventType == "CALL_LOG_OBSERVATION" })
        engine.evaluatePostCallLogVerdict(row.normalizedNumber, 5, 0, callTimestampMs = at, nowMillis = at + 2000)
        assertEquals(2, dao.getDecisionEvents(row.id).count { it.eventType == "CALL_LOG_OBSERVATION" })
    }

    @Test fun lateObservationDoesNotAttachToUnrelatedLatestCall() = runTest {
        val dao = dao()
        val row = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        engine(dao).evaluatePostCallLogVerdict(row.normalizedNumber, 5, 0,
            callTimestampMs = at - 86_400_000, nowMillis = at + 2000)
        assertTrue(dao.getDecisionEvents(row.id).none { it.eventType == "CALL_LOG_OBSERVATION" })
        assertEquals(2, dao.telemetries.size)
        assertEquals(1, dao.getAllDecisionEvents().count { it.eventType == "CALL_LOG_OBSERVATION" })
    }

    @Test fun eventsArrivingDuringUploadRemainPendingAfterSnapshotAcknowledgement() = runTest {
        val dao = dao()
        val row = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        val sentIds = dao.getDecisionEvents(row.id).map { it.eventId }
        DecisionTrace.record(dao, row.id, "USER_FEEDBACK", "USER", DecisionDetails(reason = "CONFIRMED_SPAM"))
        dao.markTelemetrySynced(listOf(row.id))
        dao.markDecisionEventsSynced(sentIds)
        assertEquals(listOf(row.id), dao.getPendingTelemetry().map { it.id })
        assertEquals(1, dao.getDecisionEvents(row.id).count { it.syncStatus == "PENDING" })
    }

    @Test fun exportKeepsLegacyFieldsAndStructuredImmutableEvents() = runTest {
        val dao = dao()
        val row = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE").telemetry
        val event = dao.getDecisionEvents(row.id).first { it.eventType == "SCREENING_DECISION" }
        val exported = Json.parseToJsonElement(TelemetryExport.encode(listOf(row), dao.getAllDecisionEvents())).jsonArray.single().jsonObject
        assertEquals(row.id, exported["id"]!!.jsonPrimitive.content)
        assertEquals(row.finalScore, exported["finalScore"]!!.jsonPrimitive.int)
        val details = exported["decisionEvents"]!!.jsonArray.single().jsonObject["details"]!!.jsonObject
        assertEquals(45, details["score"]!!.jsonPrimitive.int)
        assertTrue(details["rules"]!!.jsonArray.isNotEmpty())
        assertNotNull(DecisionTrace.details(event).ruleSetFingerprint)
        val wire = Json.encodeToString(event.toRemoteEvent())
        assertFalse(wire.contains("syncStatus"))
        assertTrue(wire.contains("event_id"))
    }

    @Test fun separateScreeningCallsWithin15SecondsKeepTheirOwnDecisions() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val a = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", at, "CALL_SCREENING_SERVICE")
        val b = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", at + 10_000, "CALL_SCREENING_SERVICE")
        assertNotEquals(a.telemetry.id, b.telemetry.id)
        assertEquals(2, dao.telemetries.size)
        assertEquals(1, dao.getDecisionEvents(a.telemetry.id).count { it.eventType == "SCREENING_DECISION" })
        assertEquals(1, dao.getDecisionEvents(b.telemetry.id).count { it.eventType == "SCREENING_DECISION" })
        engine.evaluatePostCallLogVerdict(a.telemetry.normalizedNumber, 5, 0,
            callTimestampMs = at, nowMillis = at + 12_000)
        assertTrue(dao.getDecisionEvents(a.telemetry.id).any { it.eventType == "CALL_LOG_OBSERVATION" })
        assertTrue(dao.getDecisionEvents(b.telemetry.id).none { it.eventType == "CALL_LOG_OBSERVATION" })
    }

    @Test fun reprocessPreservesPrefix6NeighborWarningAndDoesNotDuplicateQuarantineEvents() = runTest {
        val dao = dao()
        val engine = engine(dao)
        // Neighboring /8 in same /6 (313663x) is already in quarantine
        QuarantinePrefixIndex.add("31366372")
        val arrival = engine.evaluateIncomingCall("03136632192", "PASSED", at, "CALL_SCREENING_SERVICE").telemetry
        assertEquals(40, arrival.finalScore)
        assertTrue(arrival.triggeredRulesJson.contains("PREFIX6_NEIGHBOR_WARNING"))

        // User rejects the call in dialer (type=5)
        engine.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0, callTimestampMs = at, nowMillis = at + 5_000, systemBlockReason = 0)
        val afterReject = dao.getTelemetryById(arrival.id)!!
        assertEquals(85, afterReject.finalScore)
        assertTrue(afterReject.triggeredRulesJson.contains("PREFIX6_NEIGHBOR_WARNING"))
        assertTrue(afterReject.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED"))

        // Repeatedly run reprocessing and post-call reconciliation with advancing timestamps
        repeat(3) { step ->
            reprocessStored(dao)
            engine.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0,
                callTimestampMs = at, nowMillis = at + 20_000 + step * 5_000, systemBlockReason = 0)
        }

        val finalRow = dao.getTelemetryById(arrival.id)!!
        assertEquals(85, finalRow.finalScore)
        assertTrue(finalRow.triggeredRulesJson.contains("PREFIX6_NEIGHBOR_WARNING"))
        assertTrue(finalRow.triggeredRulesJson.contains("SYSTEM_CALLLOG_BLOCKED"))

        val events = dao.getDecisionEvents(arrival.id)
        assertEquals(1, events.count { it.eventType == "QUARANTINE_UPDATED" })
        assertEquals(1, events.count { it.eventType == "POST_CALL_EVALUATION" })
        assertEquals(1, events.count { it.eventType == "REPROCESS_EVALUATION" })
    }

    @Test fun coalescedRedialDoesNotOverwriteExactCallLogObservationOrPingPongEvaluation() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val arrival = engine.evaluateIncomingCall("03136630946", "PASSED", at, "CALL_SCREENING_SERVICE").telemetry
        // Exact observation at `at` (rejected in dialer, type=5)
        engine.evaluatePostCallLogVerdict(arrival.normalizedNumber, 5, 0, callTimestampMs = at, nowMillis = at + 1_000, systemBlockReason = 0)
        // Redial 11s later in CallLog (blocked at edge, type=6) falls within 15s window of `arrival`
        val changed = engine.evaluatePostCallLogVerdict(arrival.normalizedNumber, 6, 0,
            isSystemBlocked = true, callTimestampMs = at + 11_000, nowMillis = at + 12_000, systemBlockReason = 1)
        assertFalse(changed)
        val events = dao.getDecisionEvents(arrival.id)
        assertEquals(1, events.count { it.eventType == "CALL_LOG_OBSERVATION" })
        assertEquals(1, events.count { it.eventType == "POST_CALL_EVALUATION" })
        assertEquals(1, events.count { it.eventType == "QUARANTINE_UPDATED" })
    }

    @Test fun reprocessDeduplicatesUtilityRowsAndMigrates055Prefixes() = runTest {
        val dao = dao()
        // Insert duplicate utility 03110315 rows at same timestamp (one legacy with pfx7="3110315", one canonical)
        val ts = 1_791_555_733_869L
        val canonicalId = java.util.UUID.nameUUIDFromBytes("calllog-$ts-10315".toByteArray()).toString()
        dao.insertTelemetry(
            arrivalRow("legacy-10315", ts, "03110315", "10315", "3110315", "s23-ultra-history")
        )
        dao.insertTelemetry(
            arrivalRow(canonicalId, ts, "03110315", "10315", "", "s23-ultra-history")
        )
        // Insert legacy 055 row and quarantine
        dao.insertTelemetry(
            arrivalRow("call-055", at, "05531973350693", "+5505531973350693", "05531973", "s23-ultra-history")
        )
        dao.upsertQuarantine(
            br.com.spamshield.data.local.entity.PrefixQuarantineEntity(
                prefix7 = "05531973", ddd = "31", carrierGuess = "Outros",
                hitCount = 2, penaltyWeight = 70, reason = "ROBOCALL_PREFIX_CLUSTER",
                firstSeenAt = at, lastSeenAt = at, expiresAt = at + 86_400_000L
            )
        )

        reprocessStored(dao)

        val utilityRows = dao.getAllTelemetry().filter { it.rawNumber == "03110315" }
        assertEquals(1, utilityRows.size)
        assertEquals("", utilityRows.single().prefix7)

        val row055 = dao.getTelemetryById("call-055")!!
        assertEquals("+5531973350693", row055.normalizedNumber)
        assertEquals("31973350", row055.prefix7)
        assertEquals("MOBILE", row055.lineType)
        assertEquals("Claro", row055.carrierGuess)
        assertTrue(dao.getAllQuarantines().none { it.prefix7.startsWith("055") })
        assertTrue(dao.getAllQuarantines().any { it.prefix7 == "31973350" })
    }

    private fun arrivalRow(id: String, ts: Long, raw: String, norm: String, pfx: String, dev: String) =
        br.com.spamshield.data.local.entity.CallTelemetryEntity(
            id = id, timestamp = ts, deviceId = dev, rawNumber = raw, normalizedNumber = norm,
            ddd = "31", prefix7 = pfx, carrierGuess = "Outros", lineType = "UNKNOWN",
            stirShakenStatus = "NOT_VERIFIED", finalScore = 40, thresholdApplied = 70,
            outcome = "ALLOWED_LOW_SCORE", category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = 1.0, shadowModeActive = false, callDurationSec = 0,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15}]"""
        )
}
