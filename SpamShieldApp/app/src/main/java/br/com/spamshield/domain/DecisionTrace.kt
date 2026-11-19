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
    val ruleId: String, val ruleType: String, val pattern: String?, val weight: Int, val priority: Int,
    val enabled: Boolean = true
)

@Serializable
data class DecisionDetails(
    val schemaVersion: Int = 1,
    val engineRevision: String = "decision-journal-v1",
    // Null on old events: never substitute the exporting app's version for a past event.
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
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
    val dialerRejected: Boolean,
    val screeningRecorded: Boolean
)

object DecisionTrace {
    const val CURRENT_ENGINE_REVISION = "decision-journal-v2"
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
            EvaluatedRule(it.ruleId, it.ruleType, it.pattern, it.weight, it.priority, it.enabled)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(configuration).toByteArray()).joinToString("") { "%02x".format(it) }
        return snapshot(row).copy(evaluatedRules = configuration, ruleSetFingerprint = hash)
    }

    private val evaluationTypes = setOf("POST_CALL_EVALUATION", "REPROCESS_EVALUATION", "HISTORY_ASSESSMENT")

    private fun versioned(details: DecisionDetails) = details.copy(
            schemaVersion = 2,
            engineRevision = CURRENT_ENGINE_REVISION,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE
    )

    internal fun feedbackEvent(callId: String, details: DecisionDetails, at: Long, mutationId: String) =
        buildEvent(callId, "USER_FEEDBACK", "USER", details, at, mutationId)

    internal fun buildEvent(
        callId: String, type: String, source: String, details: DecisionDetails, at: Long, key: String? = null
    ): CallDecisionEventEntity =
        event(callId, type, source, json.encodeToString(versioned(details)), at, key)

    suspend fun record(
        dao: SpamShieldDao, callId: String, type: String, source: String,
        details: DecisionDetails, at: Long = System.currentTimeMillis(), key: String? = null
    ) {
        val recordedDetails = versioned(details)
        val encoded = json.encodeToString(recordedDetails)
        if (key == null && type != "SCREENING_DECISION" && type != "SCREENING_RESPONSE_SUBMITTED") {
            val existing = dao.getDecisionEvents(callId)
            if (type in evaluationTypes) {
                val previous = existing.lastOrNull { it.eventType == type && it.source == source }
                if (previous != null && sameEvaluationOutcome(details(previous), recordedDetails)) {
                    // Keep a return to a previous result when a different source changed the projection.
                    val latestProjection = existing.asReversed().asSequence().filter {
                        it.eventType in evaluationTypes ||
                            it.eventType == "SCREENING_DECISION" || it.eventType == "USER_FEEDBACK"
                    }.map(::details).firstOrNull { it.score != null && it.outcome != null }
                    if (latestProjection != null && sameProjection(latestProjection, recordedDetails)) return
                }
            } else if (existing.lastOrNull { it.eventType == type }?.let {
                    it.source == source && it.detailsJson == encoded
                } == true) {
                return
            }
        }
        dao.insertDecisionEvent(event(callId, type, source, encoded, at, key))
    }

    private fun event(
        callId: String, type: String, source: String, encoded: String, at: Long, key: String?
    ): CallDecisionEventEntity {
        val id = key?.let { UUID.nameUUIDFromBytes("$callId|$type|$source|$it".toByteArray()).toString() }
            ?: UUID.randomUUID().toString()
        return CallDecisionEventEntity(
            id, callId, at, type, source, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", encoded
        )
    }

    private val ruleOrder = compareBy<TriggeredRuleDto>({ it.rule_id }, { it.weight }, { it.meta })

    private fun sameProjection(a: DecisionDetails, b: DecisionDetails): Boolean =
        a.outcome == b.outcome &&
            a.score == b.score &&
            a.threshold == b.threshold &&
            a.category == b.category &&
            a.prefix == b.prefix &&
            a.durationSec == b.durationSec &&
            a.rules.sortedWith(ruleOrder) == b.rules.sortedWith(ruleOrder)

    internal fun sameEvaluationOutcome(a: DecisionDetails, b: DecisionDetails): Boolean =
        sameProjection(a, b) &&
            a.reason == b.reason &&
            a.distinctNumbers24h == b.distinctNumbers24h &&
            a.quarantineMatch == b.quarantineMatch &&
            a.ruleSetFingerprint == b.ruleSetFingerprint &&
            a.engineRevision == b.engineRevision &&
            a.appVersionName == b.appVersionName &&
            a.appVersionCode == b.appVersionCode

    fun eventLabel(type: String): String = when (type) {
        "SCREENING_DECISION" -> "Decisão de chegada"
        "SCREENING_RESPONSE_SUBMITTED" -> "Resposta enviada ao Android"
        "SCREENING_RESPONSE_FAILED" -> "Falha no envio ao Android"
        "SCREENING_ERROR" -> "Falha na avaliação"
        "SCREENING_TIMEOUT" -> "Prazo da triagem excedido"
        "CALL_LOG_OBSERVATION" -> "Resultado observado no Android"
        "POST_CALL_EVALUATION" -> "Aprendizado após a chamada"
        "REPROCESS_EVALUATION" -> "Reavaliação do histórico"
        "HISTORY_ASSESSMENT" -> "Avaliação de registro importado"
        "USER_FEEDBACK" -> "Avaliação do usuário"
        "QUARANTINE_UPDATED" -> "Proteção para chamadas futuras atualizada"
        else -> type
    }

    fun evidence(row: CallTelemetryEntity, events: List<CallDecisionEventEntity>): CallEvidence {
        val own = events.filter { it.callId == row.id }.sortedWith(compareBy({ it.recordedAt }, { it.eventId }))
        val arrival = own.firstOrNull { it.eventType == "SCREENING_DECISION" && it.source == "CALL_SCREENING_SERVICE" }?.let(::details)
        val response = own.lastOrNull { it.eventType == "SCREENING_RESPONSE_SUBMITTED" && it.source == "CALL_SCREENING_SERVICE" }?.let(::details)
        val observation = own.lastOrNull { it.eventType == "CALL_LOG_OBSERVATION" }?.let(::details)
        val rejected = observation?.systemCallType == 5
        val requested = response?.disallowCall == true
        // Android constants: BLOCKED_TYPE=6, BLOCK_REASON_CALL_SCREENING_SERVICE=1.
        val observed = observation?.systemCallType == 6 && observation.systemBlockReason == 1 &&
            observation.systemScreeningAppIsSpamShield == true
        val label = when {
            observation?.systemCallType == 2 || row.outcome == "OUTGOING_CALL" -> "Chamada efetuada"
            rejected -> "Rejeitada no discador"
            observation?.systemCallType == 1 && (observation.durationSec ?: 0) > 0 -> "Atendida"
            observation?.systemCallType == 3 -> "Não atendida"
            observed -> "Bloqueio do SpamShield registrado pelo Android"
            observation?.systemCallType == 6 && observation.systemBlockReason == 1 &&
                observation.systemScreeningAppIsSpamShield == false -> "Bloqueada por outro serviço de triagem"
            observation?.systemCallType == 6 && observation.systemBlockReason == 3 -> "Bloqueada pela lista do Android"
            observation?.systemCallType == 6 -> "Bloqueada pelo sistema (autoria desconhecida)"
            requested -> "Bloqueio solicitado (resultado desconhecido)"
            response?.reason == "FALLBACK_AFTER_TIMEOUT" -> "Permissão enviada no prazo de contingência"
            response?.reason == "FALLBACK_AFTER_ERROR" -> "Permissão enviada após falha do bloqueador"
            response != null -> "Passagem autorizada pelo app"
            arrival != null -> "Decisão calculada; envio não comprovado"
            else -> "Legado: decisão original não registrada"
        }
        return CallEvidence(label, arrival?.score, requested, observed, rejected, arrival != null || response != null)
    }
}
