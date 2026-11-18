package br.com.spamshield.domain

import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryProvenanceTest {
    private val json = Json { encodeDefaults = true }
    private val callAt = 1_700_000_000_000L
    private val processedAt = callAt + 7 * 86_400_000L
    private fun row(score: Int = 40, outcome: String = "ALLOWED_LOW_SCORE") = CallTelemetryEntity(
        id = "call", timestamp = callAt, deviceId = "android-edge-br", rawNumber = "test-number",
        normalizedNumber = "test-number", ddd = "31", prefix7 = "31995201", carrierGuess = "Outros",
        lineType = "MOBILE", stirShakenStatus = "NOT_VERIFIED", finalScore = score,
        thresholdApplied = 70, outcome = outcome, category = "UNKNOWN_UNVERIFIED",
        processingLatencyMs = 1.0, shadowModeActive = false,
        triggeredRulesJson = json.encodeToString(listOf(TriggeredRuleDto("NOT_IN_CONTACTS", score)))
    )
    private fun event(
        row: CallTelemetryEntity, id: String, type: String, version: String, at: Long,
        source: String = if (type == "SCREENING_DECISION") "CALL_SCREENING_SERVICE" else "HISTORY_REPROCESSOR"
    ) = CallDecisionEventEntity(id, row.id, at, type, source, version,
        json.encodeToString(DecisionTrace.snapshot(row)))

    @Test fun newVersionReprocessingDoesNotChangeOriginalCallVersion() {
        val original = row()
        val updated = row(95, "BLOCKED")
        val events = listOf(
            event(original, "arrival", "SCREENING_DECISION", "1.3.1 (15)", callAt + 10),
            event(updated, "reprocess", "REPROCESS_EVALUATION", "1.3.2 (16)", processedAt)
        )
        val provenance = TelemetryProvenance.forCall(updated, events.reversed())
        assertEquals("1.3.1 (15)", provenance.originalDecision!!.appVersion.label)
        assertEquals(15, provenance.originalDecision.appVersion.versionCode)
        assertEquals("1.3.2 (16)", provenance.currentClassification!!.appVersion.label)
        assertEquals(processedAt, provenance.currentClassification.recordedAt)
        assertEquals(listOf("1.3.1 (15)", "1.3.2 (16)"), provenance.eventAppVersions.map { it.label })
    }

    @Test fun importedHistoryKeepsOriginalVersionUnknownEvenWhenReprocessedByNewApp() {
        val row = row().copy(deviceId = "s23-ultra-history")
        val provenance = TelemetryProvenance.forCall(row,
            listOf(event(row, "reprocess", "REPROCESS_EVALUATION", "1.3.2 (16)", processedAt)))
        assertEquals("ORIGINAL_VERSION_UNKNOWN", provenance.originalVersionStatus)
        assertNull(provenance.originalDecision)
        assertEquals("1.3.2 (16)", provenance.currentClassification!!.appVersion.label)
    }

    @Test fun simulationDoesNotEstablishOriginalCallVersion() {
        val row = row()
        val provenance = TelemetryProvenance.forCall(row,
            listOf(event(row, "simulation", "SCREENING_DECISION", "1.3.2 (16)", processedAt, "SIMULATION")))
        assertNull(provenance.originalDecision)
        assertEquals("ORIGINAL_VERSION_UNKNOWN", provenance.originalVersionStatus)
    }

    @Test fun missingOrNonmatchingCurrentSnapshotHasUnknownVersion() {
        val row = row()
        val matching = event(row, "arrival", "SCREENING_DECISION", "1.3.1 (15)", callAt)
        val laterDifferent = event(row(95, "BLOCKED"), "later", "REPROCESS_EVALUATION", "1.3.2 (16)", processedAt)
        val provenance = TelemetryProvenance.forCall(row, listOf(matching, laterDifferent))
        assertNull(provenance.currentClassification)
        assertEquals("CURRENT_CLASSIFICATION_VERSION_UNKNOWN", provenance.currentClassificationStatus)
        assertNull(TelemetryProvenance.forCall(row, emptyList()).currentClassification)
    }

    @Test fun summarySeparatesOriginalCallPeriodFromLaterProcessingPeriodAndPreservesArrayRoot() {
        val row = row()
        val events = listOf(
            event(row, "arrival", "SCREENING_DECISION", "1.3.1 (15)", callAt + 10),
            event(row, "reprocess", "REPROCESS_EVALUATION", "1.3.2 (16)", processedAt)
        )
        val metadata = TelemetryExportMetadata(exportedAt = processedAt + 100,
            exporterVersionName = "1.3.3", exporterVersionCode = 17)
        val summary = TelemetryExport.summary(listOf(row), events, metadata)
        val old = summary.versions.single { it.appVersion.versionCode == 15 }
        val newer = summary.versions.single { it.appVersion.versionCode == 16 }
        assertEquals(callAt, old.firstOriginalCallAt)
        assertEquals(1, old.originalScreenedCalls)
        assertEquals(0, newer.originalScreenedCalls)
        assertNull(newer.firstOriginalCallAt)
        assertEquals(processedAt, newer.firstEventRecordedAt)
        assertEquals(1, newer.matchingCurrentClassificationRows)
        val exported = json.parseToJsonElement(TelemetryExport.encode(listOf(row), events, metadata)).jsonArray.single().jsonObject
        assertEquals(row.id, exported["id"]!!.jsonPrimitive.content)
        assertEquals("1.3.3", exported["exportMetadata"]!!.jsonObject["exporterVersionName"]!!.jsonPrimitive.content)
        assertEquals("1.3.1 (15)", exported["provenance"]!!.jsonObject["originalDecision"]!!.jsonObject["appVersion"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        val empty = json.parseToJsonElement(TelemetryExport.encodeSummary(emptyList(), emptyList(), metadata)).jsonObject
        assertEquals(0, empty["rowCount"]!!.jsonPrimitive.int)
        assertEquals(17, empty["exportMetadata"]!!.jsonObject["exporterVersionCode"]!!.jsonPrimitive.int)
    }

    @Test fun newEventsCarryExplicitVersionAndReevaluationAcrossVersionsIsRetained() = runTest {
        val dao = FakeSpamShieldDao()
        val row = row()
        dao.insertTelemetry(row)
        dao.insertDecisionEvent(event(row, "old", "REPROCESS_EVALUATION", "1.3.1 (15)", callAt))
        DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR", DecisionTrace.snapshot(row), processedAt)
        DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR", DecisionTrace.snapshot(row), processedAt + 1)
        DecisionTrace.pruneRedundantEvents(dao, listOf(row))
        val events = dao.getDecisionEvents(row.id)
        assertEquals(2, events.size)
        val current = DecisionTrace.details(events.last())
        assertEquals(2, current.schemaVersion)
        assertEquals(BuildConfig.VERSION_NAME, current.appVersionName)
        assertEquals(BuildConfig.VERSION_CODE, current.appVersionCode)
        assertEquals(DecisionTrace.CURRENT_ENGINE_REVISION, current.engineRevision)
        assertNull(DecisionTrace.details(events.first()).appVersionCode)
        assertEquals("decision-journal-v1", DecisionTrace.details(events.first()).engineRevision)
    }

    @Test fun changedRuleConfigurationProducesAnotherAssessmentEvenWhenResultIsIdentical() = runTest {
        val dao = FakeSpamShieldDao()
        val row = row()
        val first = DecisionTrace.snapshot(row).copy(ruleSetFingerprint = "configuration-a")
        val second = first.copy(ruleSetFingerprint = "configuration-b")
        DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR", first, callAt)
        DecisionTrace.record(dao, row.id, "REPROCESS_EVALUATION", "HISTORY_REPROCESSOR", second, processedAt)
        DecisionTrace.pruneRedundantEvents(dao, listOf(row))
        assertEquals(2, dao.getDecisionEvents(row.id).size)
    }

    @Test fun conflictingScreeningVersionsAreExplicitlyAmbiguous() {
        val row = row()
        val provenance = TelemetryProvenance.forCall(row, listOf(
            event(row, "a", "SCREENING_DECISION", "1.3.1 (15)", callAt),
            event(row, "b", "SCREENING_DECISION", "1.3.2 (16)", processedAt)
        ))
        assertNull(provenance.originalDecision)
        assertEquals("AMBIGUOUS_SCREENING_VERSIONS", provenance.originalVersionStatus)
    }
}
