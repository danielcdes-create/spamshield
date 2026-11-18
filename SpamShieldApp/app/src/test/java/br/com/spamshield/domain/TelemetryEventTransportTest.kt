package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.remote.toRemoteEvent
import br.com.spamshield.sync.SyncRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryEventTransportTest {
    private val transportJson = Json { encodeDefaults = true }
    private val legacyPayload = """{
        "schemaVersion":1,"engineRevision":"decision-journal-v1",
        "ruleSetFingerprint":"legacy-fingerprint","score":40,"outcome":"ALLOWED_LOW_SCORE",
        "evaluatedRules":[{"ruleId":"TEST_RULE","ruleType":"HEURISTIC_WEIGHT","pattern":null,"weight":25,"priority":1}],
        "rules":[{"rule_id":"NOT_IN_CONTACTS","weight":25,"meta":null}]
    }"""

    private fun event(payload: String, version: String = "1.3.2 (16)") = CallDecisionEventEntity(
        eventId = "original-event", callId = "call", recordedAt = 2000L,
        eventType = "SCREENING_DECISION", source = "CALL_SCREENING_SERVICE",
        appVersion = version, detailsJson = payload, syncStatus = "PENDING"
    )

    private fun row() = CallTelemetryEntity(
        id = "call", timestamp = 1000L, rawNumber = "fixture-number", normalizedNumber = "fixture-number",
        ddd = "31", prefix7 = "31995201", carrierGuess = "Outros", lineType = "MOBILE",
        stirShakenStatus = "NOT_VERIFIED", finalScore = 40, thresholdApplied = 70,
        outcome = "ALLOWED_LOW_SCORE", category = "UNKNOWN_UNVERIFIED", processingLatencyMs = 1.0,
        shadowModeActive = false, triggeredRulesJson = "[]", syncStatus = "PENDING"
    )

    @Test fun legacyTransportDoesNotInjectVersionOrRuleDefaultsAndKeepsEnvelope() {
        val original = event(legacyPayload)
        val wire = transportJson.parseToJsonElement(transportJson.encodeToString(original.toRemoteEvent())).jsonObject
        assertEquals(transportJson.parseToJsonElement(legacyPayload), wire["details"])
        val details = wire["details"]!!.jsonObject
        assertFalse(details.containsKey("appVersionName"))
        assertFalse(details.containsKey("appVersionCode"))
        assertFalse(details["evaluatedRules"]!!.jsonArray.single().jsonObject.containsKey("enabled"))
        assertEquals("legacy-fingerprint", details["ruleSetFingerprint"]!!.jsonPrimitive.content)
        assertEquals(original.eventId, wire["event_id"]!!.jsonPrimitive.content)
        assertEquals(original.recordedAt, wire["recorded_at"]!!.jsonPrimitive.long)
        assertEquals(original.eventType, wire["event_type"]!!.jsonPrimitive.content)
        assertEquals(original.source, wire["source"]!!.jsonPrimitive.content)
        assertEquals(original.appVersion, wire["app_version"]!!.jsonPrimitive.content)
        assertFalse(wire.containsKey("syncStatus"))
        assertEquals(legacyPayload, original.detailsJson)
    }

    @Test fun unknownFieldsNestedValuesAndNullsSurviveRepositoryTransport() {
        val payload = """{"score":40,"futureField":{"nullValue":null,"list":[true,2,3.25,{"opaque":"kept"}]},"futureFlag":false}"""
        val original = event(payload)
        val repository = SyncRepository(FakeSpamShieldDao(), SettingsDataStore())
        val wire = with(repository) { row().toRemoteDto(listOf(original)) }
        val serialized = transportJson.parseToJsonElement(transportJson.encodeToString(wire)).jsonObject
        val transported = serialized["decision_events"]!!.jsonArray.single().jsonObject["details"]!!
        assertEquals(transportJson.parseToJsonElement(payload), transported)
        assertFalse(transported.jsonObject.containsKey("schemaVersion"))
        assertFalse(transported.jsonObject.containsKey("engineRevision"))
        assertTrue(transported.jsonObject["futureField"]!!.jsonObject["nullValue"] is JsonNull)
    }

    @Test fun explicitNewMetadataAndDisabledRuleArePreservedWithoutCanonicalization() {
        val payload = """{"schemaVersion":2,"engineRevision":"decision-journal-v2","appVersionName":"1.3.3","appVersionCode":17,"evaluatedRules":[{"ruleId":"OFF","enabled":false}],"optional":null}"""
        val wire = event(payload, "1.3.3 (17)").toRemoteEvent()
        assertEquals(transportJson.parseToJsonElement(payload), wire.details)
        assertEquals(false, wire.details.jsonObject["evaluatedRules"]!!.jsonArray.single().jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun exportRetainsOriginalDetailsAndUnknownFieldsWithSameEventId() {
        val payload = """{"schemaVersion":1,"score":40,"outcome":"ALLOWED_LOW_SCORE","unrecognizedAuditField":{"value":"preserved"}}"""
        val original = event(payload)
        val exported = transportJson.parseToJsonElement(TelemetryExport.encode(listOf(row()), listOf(original))).jsonArray.single().jsonObject
        val exportedEvent = exported["decisionEvents"]!!.jsonArray.single().jsonObject
        assertEquals(original.eventId, exportedEvent["event_id"]!!.jsonPrimitive.content)
        assertEquals(transportJson.parseToJsonElement(payload), exportedEvent["details"])
        assertFalse(exportedEvent["details"]!!.jsonObject.containsKey("appVersionCode"))
    }

    @Test fun malformedJournalJsonFailsInsteadOfInventingAnEmptyEventAndLeavesItPending() = runTest {
        val dao = FakeSpamShieldDao()
        val original = event("""{"score":40,""")
        val row = row()
        dao.insertTelemetry(row)
        dao.insertDecisionEvent(original)
        val repository = SyncRepository(dao, SettingsDataStore())
        assertThrows(SerializationException::class.java) { with(repository) { row.toRemoteDto(listOf(original)) } }
        assertThrows(SerializationException::class.java) { TelemetryExport.encode(listOf(row), listOf(original)) }
        assertEquals(original, dao.getDecisionEvents(row.id).single())
        assertEquals("PENDING", dao.getDecisionEvents(row.id).single().syncStatus)
        assertEquals("PENDING", dao.getTelemetryById(row.id)!!.syncStatus)
    }
}
