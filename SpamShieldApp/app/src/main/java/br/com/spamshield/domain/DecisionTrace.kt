package br.com.spamshield.domain

import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class EvaluatedRule(
    val ruleId: String, val ruleType: String, val pattern: String?, val weight: Int, val priority: Int
)

@Serializable
data class DecisionDetails(
    val schemaVersion: Int = 1,
    val engineRevision: String = "decision-journal-v1",
    val ruleSetFingerprint: String? = null,
    val evaluatedRules: List<EvaluatedRule> = emptyList(),
    val distinctNumbers24h: Int? = null,
    val quarantineMatch: String? = null,
    val quarantineCacheReady: Boolean? = null,
    val quarantineCacheSize: Int? = null,
    val outcome: String? = null,
    val score: Int? = null,
    val threshold: Int? = null,
    val category: String? = null,
    val rules: List<TriggeredRuleDto> = emptyList(),
    val rawNumber: String? = null,
    val normalizedNumber: String? = null,
    val prefix: String? = null,
    val stirShakenStatus: String? = null,
    val shadowMode: Boolean? = null,
    val disallowCall: Boolean? = null,
    val rejectCall: Boolean? = null,
    val skipNotification: Boolean? = null,
    val skipCallLog: Boolean? = null,
    val elapsedMs: Double? = null,
    val durationSec: Int? = null,
    val systemCallType: Int? = null,
    val systemBlockReason: Int? = null,
    val systemScreeningComponent: String? = null,
    val systemScreeningAppIsSpamShield: Boolean? = null,
    val observedCallTimestamp: Long? = null,
    val reason: String? = null
)

data class CallEvidence(
    val label: String,
    val arrivalScore: Int?,
    val blockRequested: Boolean,
    val blockObserved: Boolean,
    val dialerRejected: Boolean
)

object DecisionTrace {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun details(event: CallDecisionEventEntity): DecisionDetails =
        runCatching { json.decodeFromString<DecisionDetails>(event.detailsJson) }.getOrDefault(DecisionDetails())

    fun snapshot(row: CallTelemetryEntity) = DecisionDetails(
        outcome = row.outcome, score = row.finalScore, threshold = row.thresholdApplied,
        category = row.category, rules = UserFeedbackAudit.decode(row.triggeredRulesJson),
        rawNumber = row.rawNumber, normalizedNumber = row.normalizedNumber, prefix = row.prefix7,
        stirShakenStatus = row.stirShakenStatus, shadowMode = row.shadowModeActive,
        durationSec = row.callDurationSec
    )

    fun configuredSnapshot(row: CallTelemetryEntity, rules: Collection<ActiveRuleEntity>): DecisionDetails {
        val configuration = rules.sortedBy { it.ruleId }.map {
            EvaluatedRule(it.ruleId, it.ruleType, it.pattern, it.weight, it.priority)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(configuration).toByteArray()).joinToString("") { "%02x".format(it) }
        return snapshot(row).copy(evaluatedRules = configuration, ruleSetFingerprint = hash)
    }

    suspend fun record(
        dao: SpamShieldDao, callId: String, type: String, source: String,
        details: DecisionDetails, at: Long = System.currentTimeMillis(), key: String? = null
    ) {
        val encoded = json.encodeToString(details)
        // Repeated observations/reprocessing with unchanged evidence do not grow the journal.
        if (key == null && type != "SCREENING_DECISION" && type != "SCREENING_RESPONSE_SUBMITTED") {
            val existing = dao.getDecisionEvents(callId)
            if (type == "QUARANTINE_UPDATED") {
                val baseReason = details.reason?.substringBefore("; expiresAt=")
                val alreadyRecorded = existing.any { ev ->
                    ev.eventType == "QUARANTINE_UPDATED" &&
                        details(ev).let { d ->
                            d.prefix == details.prefix &&
                                d.reason?.substringBefore("; expiresAt=") == baseReason
                        }
                }
                if (alreadyRecorded) return
            } else {
                val latest = existing.lastOrNull { it.eventType == type && it.source == source }
                if (latest != null) {
                    if (latest.detailsJson == encoded) return
                    if (type == "POST_CALL_EVALUATION" || type == "REPROCESS_EVALUATION" || type == "HISTORY_ASSESSMENT") {
                        if (sameEvaluationOutcome(details(latest), details)) return
                    }
                }
            }
        }
        val id = key?.let { UUID.nameUUIDFromBytes("$callId|$type|$source|$it".toByteArray()).toString() }
            ?: UUID.randomUUID().toString()
        dao.insertDecisionEvent(CallDecisionEventEntity(
            id, callId, at, type, source, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", encoded
        ))
    }

    internal fun sameEvaluationOutcome(a: DecisionDetails, b: DecisionDetails): Boolean =
        a.outcome == b.outcome &&
            a.score == b.score &&
            a.threshold == b.threshold &&
            a.category == b.category &&
            a.prefix == b.prefix &&
            a.rules == b.rules

    suspend fun pruneRedundantEvents(dao: SpamShieldDao, rows: List<CallTelemetryEntity>) {
        val rowById = rows.associateBy { it.id }
        val allEvents = dao.getAllDecisionEvents()
        val toDelete = mutableListOf<String>()

        for ((callId, events) in allEvents.groupBy { it.callId }) {
            val row = rowById[callId]
            val obsEvents = events.filter { it.eventType == "CALL_LOG_OBSERVATION" }
            val closestObsTimestamp = if (row != null && obsEvents.size > 1) {
                obsEvents.mapNotNull { details(it).observedCallTimestamp }
                    .minByOrNull { kotlin.math.abs(it - row.timestamp) }
            } else {
                null
            }

            val seenQuarantine = mutableSetOf<Pair<String?, String?>>()
            val lastEvalByTypeSource = mutableMapOf<Pair<String, String>, DecisionDetails>()

            for (ev in events) {
                val d = details(ev)
                when (ev.eventType) {
                    "CALL_LOG_OBSERVATION" -> {
                        val obsTs = d.observedCallTimestamp
                        if (closestObsTimestamp != null && obsTs != null && obsTs != closestObsTimestamp) {
                            toDelete.add(ev.eventId)
                        }
                    }
                    "QUARANTINE_UPDATED" -> {
                        val qKey = d.prefix to d.reason?.substringBefore("; expiresAt=")
                        if (!seenQuarantine.add(qKey)) {
                            toDelete.add(ev.eventId)
                        }
                    }
                    "POST_CALL_EVALUATION", "REPROCESS_EVALUATION", "HISTORY_ASSESSMENT" -> {
                        val tsKey = ev.eventType to ev.source
                        val prev = lastEvalByTypeSource[tsKey]
                        if (prev != null && sameEvaluationOutcome(prev, d)) {
                            toDelete.add(ev.eventId)
                        } else {
                            lastEvalByTypeSource[tsKey] = d
                        }
                    }
                }
            }
        }

        if (toDelete.isNotEmpty()) {
            toDelete.chunked(500).forEach { chunk ->
                dao.deleteDecisionEvents(chunk)
            }
        }
    }

    fun eventLabel(type: String): String = when (type) {
        "SCREENING_DECISION" -> "Decisão de chegada"
        "SCREENING_RESPONSE_SUBMITTED" -> "Resposta enviada ao Android"
        "SCREENING_RESPONSE_FAILED" -> "Falha no envio ao Android"
        "SCREENING_ERROR" -> "Falha na avaliação"
        "CALL_LOG_OBSERVATION" -> "Resultado observado no Android"
        "POST_CALL_EVALUATION" -> "Aprendizado após a chamada"
        "REPROCESS_EVALUATION" -> "Reavaliação do histórico"
        "HISTORY_ASSESSMENT" -> "Avaliação de registro importado"
        "USER_FEEDBACK" -> "Avaliação do usuário"
        "QUARANTINE_UPDATED" -> "Proteção para chamadas futuras atualizada"
        else -> type
    }

    fun evidence(row: CallTelemetryEntity, events: List<CallDecisionEventEntity>): CallEvidence {
        val own = events.filter { it.callId == row.id }
        val arrival = own.firstOrNull { it.eventType == "SCREENING_DECISION" && it.source == "CALL_SCREENING_SERVICE" }?.let(::details)
        val response = own.lastOrNull { it.eventType == "SCREENING_RESPONSE_SUBMITTED" && it.source == "CALL_SCREENING_SERVICE" }?.let(::details)
        val observation = own.lastOrNull { it.eventType == "CALL_LOG_OBSERVATION" }?.let(::details)
        val rejected = observation?.systemCallType == 5
        val requested = response?.disallowCall == true
        // Android constants: BLOCKED_TYPE=6, BLOCK_REASON_CALL_SCREENING_SERVICE=1.
        val observed = requested && observation?.systemCallType == 6 && observation.systemBlockReason == 1 &&
            observation.systemScreeningAppIsSpamShield != false
        val label = when {
            rejected -> "Rejeitada no discador"
            observation?.systemCallType == 1 && (observation.durationSec ?: 0) > 0 -> "Atendida"
            observation?.systemCallType == 3 -> "Não atendida"
            observed -> "Bloqueio registrado pelo Android"
            observation?.systemCallType == 6 -> "Bloqueada pelo sistema; autoria não comprovada"
            requested -> "Bloqueio solicitado; resultado não confirmado"
            response?.reason == "FALLBACK_AFTER_ERROR" -> "Permissão enviada após falha do bloqueador"
            response != null -> "Passagem autorizada pelo app"
            arrival != null -> "Decisão calculada; envio não comprovado"
            else -> "Legado: decisão original não registrada"
        }
        return CallEvidence(label, arrival?.score, requested, observed, rejected)
    }
}
