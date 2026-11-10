package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.TriggeredRuleDto

/**
 * Shared history-import and reprocess scoring. Both paths call [HeuristicScorer.score]
 * and then [HeuristicScorer.finalize] so the audit equation and the VoIP mutex stay in one place.
 */
object HistoryCallScorer {
    fun score(
        input: ScoreInput,
        rejectedInDialer: Boolean = false,
        distinctNumbers: Int = 1,
        userFeedback: String = "UNRATED"
    ): ScoreResult {
        val base = HeuristicScorer.score(input)
        val rules = base.triggeredRules.toMutableList()
        if (
            rejectedInDialer &&
            base.finalScore < input.thresholdApplied &&
            rules.none { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
        ) {
            rules.add(TriggeredRuleDto("SYSTEM_CALLLOG_BLOCKED", 45, "Rejeitado no Discador"))
        }
        if (
            distinctNumbers >= 2 &&
            !rejectedInDialer &&
            rules.none { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" }
        ) {
            rules.add(
                TriggeredRuleDto(
                    "PREFIX_NEIGHBORHOOD_BURST",
                    60,
                    "distinct_24h=$distinctNumbers"
                )
            )
        }
        if (
            input.ddd.isNotEmpty() &&
            input.ddd != "00" &&
            input.ddd != input.userLocalDdd &&
            rules.none { it.rule_id == "EXTERNAL_DDD_UNKNOWN" }
        ) {
            rules.add(
                TriggeredRuleDto(
                    "EXTERNAL_DDD_UNKNOWN",
                    30,
                    "DDD ${input.ddd} != ${input.userLocalDdd}"
                )
            )
        }
        if (userFeedback == "CONFIRMED_SPAM" && rules.none { it.rule_id == UserFeedbackAudit.OVERRIDE_RULE }) {
            rules.add(TriggeredRuleDto(UserFeedbackAudit.OVERRIDE_RULE, 100, UserFeedbackAudit.OVERRIDE_META))
        }
        val finalized = HeuristicScorer.finalize(rules, input)
        if (userFeedback == "CONFIRMED_SPAM" && base.outcome == "ALLOWED_LOW_SCORE") {
            return finalized.copy(outcome = "USER_BLOCKED_RETROACTIVE", category = "USER_REPORTED_SPAM")
        }
        return finalized
    }
}
