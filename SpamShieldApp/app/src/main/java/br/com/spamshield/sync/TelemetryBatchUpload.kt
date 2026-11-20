package br.com.spamshield.sync

import br.com.spamshield.data.remote.BatchTelemetryResponseDto
import br.com.spamshield.data.remote.CallTelemetryRemoteDto
import br.com.spamshield.data.remote.FeatureBatchAck
import br.com.spamshield.data.remote.FeatureEventRemoteDto
import retrofit2.HttpException

/** Isolate legacy HTTP 409 and size-limit responses. Each successful sub-batch is acknowledged immediately. */
internal suspend fun uploadTelemetryBatch(
    batch: List<CallTelemetryRemoteDto>,
    send: suspend (List<CallTelemetryRemoteDto>) -> BatchTelemetryResponseDto,
    acknowledge: suspend (CallTelemetryRemoteDto, Set<String>) -> Unit,
    defer: (CallTelemetryRemoteDto) -> Unit
) {
    uploadIsolatingRejectedItems(batch, send, defer) { sent, response ->
        val acceptedCalls = response.accepted_ids.toSet() - response.conflicting_call_ids.toSet()
        val acceptedEvents = response.accepted_event_ids.toSet()
        for (call in sent) {
            if (call.id in acceptedCalls) {
                acknowledge(call, call.decision_events.map { it.event_id }.toSet().intersect(acceptedEvents))
            } else {
                defer(call)
            }
        }
    }
}

internal suspend fun uploadFeatureBatch(
    batch: List<FeatureEventRemoteDto>,
    send: suspend (List<FeatureEventRemoteDto>) -> FeatureBatchAck,
    acknowledge: suspend (Set<String>) -> Unit
): Int {
    var deferred = 0
    try {
        uploadIsolatingRejectedItems(batch, send, { deferred++ }) { sent, ack ->
            val accepted = sent.mapTo(hashSetOf()) { it.event_id }
                .intersect(ack.accepted_event_ids.toSet()) - ack.conflicting_event_ids.toSet()
            if (accepted.isNotEmpty()) acknowledge(accepted)
            deferred += sent.size - accepted.size
        }
    } catch (error: HttpException) {
        if (error.code() != 404 && error.code() != 405) throw error
        return batch.size
    }
    return deferred
}

private suspend fun <T, R> uploadIsolatingRejectedItems(
    batch: List<T>, send: suspend (List<T>) -> R, defer: (T) -> Unit,
    process: suspend (List<T>, R) -> Unit
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
            uploadIsolatingRejectedItems(batch.subList(0, midpoint), send, defer, process)
            uploadIsolatingRejectedItems(batch.subList(midpoint, batch.size), send, defer, process)
        }
        return
    }
    process(batch, response)
}
