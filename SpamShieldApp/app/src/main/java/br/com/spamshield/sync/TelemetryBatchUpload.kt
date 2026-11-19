package br.com.spamshield.sync

import br.com.spamshield.data.remote.BatchTelemetryResponseDto
import br.com.spamshield.data.remote.CallTelemetryRemoteDto
import retrofit2.HttpException

/** Isolate legacy HTTP 409 and size-limit responses. Each successful sub-batch is acknowledged immediately. */
internal suspend fun uploadTelemetryBatch(
    batch: List<CallTelemetryRemoteDto>,
    send: suspend (List<CallTelemetryRemoteDto>) -> BatchTelemetryResponseDto,
    acknowledge: suspend (CallTelemetryRemoteDto, Set<String>) -> Unit,
    defer: (CallTelemetryRemoteDto) -> Unit
) {
    if (batch.isEmpty()) return
    val response = try {
        send(batch)
    } catch (error: HttpException) {
        if (error.code() != 409 && error.code() != 413) throw error
        if (batch.size == 1) {
            defer(batch.single())
        } else {
            val midpoint = batch.size / 2
            uploadTelemetryBatch(batch.subList(0, midpoint), send, acknowledge, defer)
            uploadTelemetryBatch(batch.subList(midpoint, batch.size), send, acknowledge, defer)
        }
        return
    }

    val acceptedCalls = response.accepted_ids.toSet() - response.conflicting_call_ids.toSet()
    val acceptedEvents = response.accepted_event_ids.toSet()
    for (call in batch) {
        if (call.id in acceptedCalls) {
            acknowledge(call, call.decision_events.map { it.event_id }.toSet().intersect(acceptedEvents))
        } else {
            defer(call)
        }
    }
}
