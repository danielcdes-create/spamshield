package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.remote.toRemoteEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

object TelemetryExport {
    private val json = Json { encodeDefaults = true }

    /** Existing root array and fields stay compatible; each row gains its full journal. */
    fun encode(rows: List<CallTelemetryEntity>, events: List<CallDecisionEventEntity>): String {
        val byCall = events.groupBy { it.callId }
        return JsonArray(rows.map { row ->
            JsonObject(json.encodeToJsonElement(row).jsonObject +
                ("decisionEvents" to json.encodeToJsonElement(byCall[row.id].orEmpty().map { it.toRemoteEvent() })))
        }).toString()
    }
}
