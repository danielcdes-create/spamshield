package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.serialization.Serializable

@Serializable
data class RuleChange(val ruleId: String, val weight: Int, val enabled: Boolean)

@Serializable
data class LabExperiment(
    val id: String,
    val change: RuleChange,
    val startedAt: Long,
    val expiresAt: Long?,
    val baseRule: RuleChange,
    val baseFingerprint: String,
    val candidateFingerprint: String
)

@Serializable
data class ArrivalContext(
    val carrier: String?,
    val lineType: String,
    val localDdd: String,
    val hideBlockedFromCallLog: Boolean,
    val contextualRules: List<TriggeredRuleDto>,
    val prefix6QuarantineCount: Int? = null,
    val prefix5QuarantineCount: Int? = null
)

@Serializable
data class LabComparison(
    val originalEventId: String,
    val baseFingerprint: String?,
    val candidateFingerprint: String?,
    val baseScore: Int?,
    val candidateScore: Int?,
    val threshold: Int?,
    val baseAction: String?,
    val candidateAction: String?,
    val direction: String,
    val reason: String,
    val baseRules: List<TriggeredRuleDto>,
    val candidateRules: List<TriggeredRuleDto>
)

object RuleLaboratory {
    val lifecycleTypes = listOf("LAB_SHADOW_STARTED", "LAB_ENDED", "LAB_DISCARDED", "LAB_APPLIED")
    val directions = listOf("BLOCK_TO_ALLOW", "ALLOW_TO_BLOCK", "UNCHANGED", "INSUFFICIENT")

    fun running(events: List<br.com.spamshield.data.local.entity.FeatureEventEntity>): LabExperiment? {
        val ended = events.filter { it.eventType in lifecycleTypes && it.eventType != "LAB_SHADOW_STARTED" }
            .mapTo(hashSetOf()) { it.resourceId }
        return events.lastOrNull { it.eventType == "LAB_SHADOW_STARTED" && it.resourceId !in ended }
            ?.let(FeatureJournal::payload)?.experiment
    }

    fun active(events: List<br.com.spamshield.data.local.entity.FeatureEventEntity>, at: Long): LabExperiment? {
        val endById = events.filter { it.eventType in lifecycleTypes && it.eventType != "LAB_SHADOW_STARTED" }
            .groupBy { it.resourceId }.mapValues { (_, history) -> history.minOf { it.effectiveAt } }
        return events.asReversed().asSequence().filter { it.eventType == "LAB_SHADOW_STARTED" }
            .mapNotNull { FeatureJournal.payload(it).experiment }
            .firstOrNull { experiment ->
                at >= experiment.startedAt &&
                    at < minOf(experiment.expiresAt ?: Long.MAX_VALUE, endById[experiment.id] ?: Long.MAX_VALUE)
            }
    }

    private val builtIns = listOf(
        Triple("PREFIX5_CHIPEIRA_FARM", "Faixa de chip farm", 35),
        Triple("PREFIX6_NEIGHBOR_WARNING", "Vizinhança da quarentena", 15),
        Triple("EXTERNAL_DDD_UNKNOWN", "DDD externo desconhecido", 30),
        Triple("PREFIX_QUARANTINE_HIT", "Correspondência exata de quarentena", QuarantinePrefixIndex.EXACT_WEIGHT),
        Triple("PREFIX7_BROAD_QUARANTINE_HIT", "Faixa ampla em quarentena", QuarantinePrefixIndex.BROAD_WEIGHT),
        Triple("LEADING_ZERO_TRUNK_DIALING", "Cabeçalho de discagem com zero", 25),
        Triple("MALFORMED_NUMBER_SPOOFING", "Formato de número inválido", 85),
        Triple("BLACKLIST_MG_VOIP_PABX", "Troncos VoIP em MG", 80),
        Triple("ANATEL_PABX_VOIP_RANGE", "Faixa VoIP da Anatel", 35)
    ).map { (id, label, weight) ->
        br.com.spamshield.data.local.entity.ActiveRuleEntity(id, label, "HEURISTIC_WEIGHT", null, weight, 100, true, 0)
    }

    private val legacyAdditiveIds = setOf("PREFIX5_CHIPEIRA_FARM", "PREFIX6_NEIGHBOR_WARNING", "EXTERNAL_DDD_UNKNOWN")

    fun catalog(rules: List<br.com.spamshield.data.local.entity.ActiveRuleEntity>) =
        (rules + builtIns).distinctBy { it.ruleId }.filterNot { it.ruleType == "SHORT_CIRCUIT_ALLOW" || it.ruleId in HeuristicScorer.PROTECTED_ALLOW_RULES }

    fun candidate(rules: List<EvaluatedRule>, change: RuleChange): List<EvaluatedRule> =
        rules.map { if (it.ruleId == change.ruleId) it.copy(weight = change.weight, enabled = change.enabled) else it }

    fun compare(original: CallDecisionEventEntity, change: RuleChange, ambiguous: Boolean = false): LabComparison {
        require(original.eventType == "SCREENING_DECISION" && original.source == "CALL_SCREENING_SERVICE")
        val d = DecisionTrace.details(original)
        fun clean(rules: List<TriggeredRuleDto>) = rules.map { it.copy(meta = null) }
        val baseAction = d.outcome?.let { if (it == "BLOCKED") "BLOCK" else "ALLOW" }
        fun result(score: Int?, action: String?, direction: String, reason: String,
                   rules: List<TriggeredRuleDto> = emptyList(), fingerprint: String? = null) =
            LabComparison(original.eventId, d.ruleSetFingerprint, fingerprint, d.score, score,
                d.threshold, baseAction, action, direction, reason, clean(d.rules), clean(rules))

        if (ambiguous) return result(null, null, "INSUFFICIENT", "AMBIGUOUS_ARRIVAL_DECISION")
        if (d.outcome in setOf("ALLOWED_CONTACT", "ALLOWED_EMERGENCY_REPEAT", "ALLOWED_OUTBOUND_GRACE") &&
            d.score == 0 && d.threshold != null) {
            return result(0, "ALLOW", "UNCHANGED", "PROTECTED_ALLOW", d.rules)
        }
        val context = d.arrivalContext
        // These additive arrival rules do not influence matching, exclusive groups or protected shortcuts.
        val independentlyWeighted = change.ruleId in legacyAdditiveIds
        if (context == null && independentlyWeighted && d.ruleSetFingerprint != null && d.score != null &&
            d.threshold != null && d.shadowMode != null && baseAction != null &&
            HeuristicScorer.equation(d.rules) == d.score && d.durationSec == null
        ) {
            val activeRule = d.evaluatedRules.find { it.ruleId == change.ruleId }
            val triggered = d.rules.any { it.rule_id == change.ruleId }
            if (triggered || activeRule?.enabled != false || !change.enabled) {
                val alternative = d.rules.mapNotNull {
                    if (it.rule_id != change.ruleId) it else if (change.enabled) it.copy(weight = change.weight) else null
                }
                val score = HeuristicScorer.equation(alternative)
                val action = if (HeuristicScorer.resolveOutcome(score, d.threshold, null, d.shadowMode) == "BLOCKED") "BLOCK" else "ALLOW"
                val direction = if (action == baseAction) "UNCHANGED" else if (action == "ALLOW") "BLOCK_TO_ALLOW" else "ALLOW_TO_BLOCK"
                val fingerprint = DecisionTrace.digest("legacy|${d.ruleSetFingerprint}|${change.ruleId}|${change.weight}|${change.enabled}")
                return result(score, action, direction, change.ruleId, alternative, fingerprint)
            }
        }
        if (context == null || d.rawNumber == null || d.score == null || d.threshold == null ||
            d.shadowMode == null || d.quarantineMatch == null || d.stirShakenStatus == null || baseAction == null) {
            return result(null, null, "INSUFFICIENT", "ARRIVAL_CONTEXT_UNAVAILABLE")
        }
        if (d.evaluatedRules.none { it.ruleId == change.ruleId }) {
            return result(null, null, "INSUFFICIENT", "RULE_CONFIGURATION_UNAVAILABLE")
        }
        val match = PrefixQuarantineMatch.entries.find { it.name == d.quarantineMatch }
            ?: return result(null, null, "INSUFFICIENT", "PREFIX_MATCH_UNAVAILABLE")
        val phone = PhoneNormalizer.normalize(d.rawNumber, context.localDdd)
        fun evaluate(config: List<EvaluatedRule>): List<TriggeredRuleDto> {
            val input = ScoreInput(
                rawNumber = d.rawNumber, normalizedNumber = phone.normalizedNumber, ddd = phone.ddd,
                prefix7 = phone.storedPrefix7, subscriberNumber = phone.subscriberNumber,
                carrierGuess = context.carrier, lineType = context.lineType, stirShakenStatus = d.stirShakenStatus,
                quarantineMatch = match, thresholdApplied = d.threshold, userLocalDdd = context.localDdd,
                cspExtracted = phone.cspExtracted, isShortOrUtilityCode = phone.isShortOrUtilityCode,
                shadowMode = d.shadowMode, ruleWeights = config.associate { it.ruleId to it.weight },
                disabledRuleIds = config.filterNot { it.enabled }.mapTo(hashSetOf()) { it.ruleId },
                extraRegexRules = config.mapNotNull {
                    val pattern = it.pattern
                    if (it.enabled && it.ruleType == "REGEX_MATCH" && !pattern.isNullOrBlank()) {
                        CompiledRegexRule(it.ruleId, it.weight, pattern, ScreeningPatternCache.pattern(pattern))
                    } else null
                }
            )
            return HeuristicScorer.configuredRules(HeuristicScorer.score(input).triggeredRules + context.contextualRules, input)
        }
        val originalRules = runCatching { evaluate(d.evaluatedRules) }.getOrNull()
        if (originalRules == null || HeuristicScorer.equation(originalRules) != d.score ||
            clean(originalRules).sortedBy { it.rule_id } != clean(d.rules).sortedBy { it.rule_id }) {
            return result(null, null, "INSUFFICIENT", "BASE_RECONSTRUCTION_MISMATCH")
        }
        val candidate = candidate(d.evaluatedRules, change)
        val alternative = runCatching { evaluate(candidate) }.getOrNull()
            ?: return result(null, null, "INSUFFICIENT", "CANDIDATE_RECONSTRUCTION_FAILED")
        val score = HeuristicScorer.equation(alternative)
        val action = if (HeuristicScorer.resolveOutcome(score, d.threshold, null, d.shadowMode) == "BLOCKED") "BLOCK" else "ALLOW"
        val direction = when {
            action == baseAction -> "UNCHANGED"
            action == "ALLOW" -> "BLOCK_TO_ALLOW"
            else -> "ALLOW_TO_BLOCK"
        }
        return result(score, action, direction, change.ruleId, alternative,
            DecisionTrace.configurationFingerprint(candidate, d.threshold, d.shadowMode, context.localDdd, context.hideBlockedFromCallLog))
    }
}
