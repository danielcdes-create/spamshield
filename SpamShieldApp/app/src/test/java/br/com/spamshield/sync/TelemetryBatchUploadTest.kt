package br.com.spamshield.sync

import br.com.spamshield.data.remote.AuditRemoteDto
import br.com.spamshield.data.remote.BatchTelemetryResponseDto
import br.com.spamshield.data.remote.CallDetailsRemoteDto
import br.com.spamshield.data.remote.CallTelemetryRemoteDto
import br.com.spamshield.data.remote.DecisionEventRemoteDto
import br.com.spamshield.data.remote.DecisionVectorRemoteDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class TelemetryBatchUploadTest {
    private fun call(id: String) = CallTelemetryRemoteDto(
        id = id,
        timestamp = 100,
        device_id = "test",
        call_details = CallDetailsRemoteDto("", "", "", "", null, "UNKNOWN", "NOT_VERIFIED"),
        decision_vector = DecisionVectorRemoteDto(
            0, 70, "ALLOWED_LOW_SCORE", "UNKNOWN", 0.0, triggered_rules = emptyList()
        ),
        audit = AuditRemoteDto("UNRATED"),
        decision_events = listOf(DecisionEventRemoteDto("event-$id", 101, "HISTORY_ASSESSMENT", "TEST", "TEST", JsonObject(emptyMap())))
    )

    @Test fun partialAcceptanceAcknowledgesOnlyEventsFromAcceptedCalls() = runTest {
        val acknowledged = mutableMapOf<String, Set<String>>()
        val deferred = mutableListOf<String>()
        uploadTelemetryBatch(
            listOf(call("accepted"), call("conflict"), call("unknown")),
            send = {
                BatchTelemetryResponseDto(
                    accepted_ids = listOf("accepted", "conflict", "outside"),
                    accepted_event_ids = listOf("event-accepted", "event-conflict", "event-outside"),
                    conflicting_call_ids = listOf("conflict")
                )
            },
            acknowledge = { call, events -> acknowledged[call.id] = events },
            defer = { deferred.add(it.id) }
        )
        assertEquals(mapOf("accepted" to setOf("event-accepted")), acknowledged)
        assertEquals(listOf("conflict", "unknown"), deferred)
    }

    @Test fun legacy409And413IsolateOnlyProblemCallsAndContinueWithHealthyCalls() = runTest {
        val acknowledged = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        uploadTelemetryBatch(
            listOf(call("before"), call("conflict"), call("oversize"), call("after")),
            send = { batch ->
                val ids = batch.map { it.id }
                if ("conflict" in ids) throw HttpException(Response.error<Unit>(409, "".toResponseBody()))
                if ("oversize" in ids) throw HttpException(Response.error<Unit>(413, "".toResponseBody()))
                BatchTelemetryResponseDto(ids, batch.flatMap { row -> row.decision_events.map { it.event_id } })
            },
            acknowledge = { call, _ -> acknowledged.add(call.id) },
            defer = { deferred.add(it.id) }
        )
        assertEquals(listOf("before", "after"), acknowledged)
        assertEquals(listOf("conflict", "oversize"), deferred)
    }
}
