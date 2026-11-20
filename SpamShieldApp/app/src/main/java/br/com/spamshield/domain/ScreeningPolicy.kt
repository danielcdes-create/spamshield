package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.serialization.Serializable

@Serializable
data class ScreeningPolicyDecision(
    val normalAction: String,
    val chosenAction: String,
    val reason: String,
    val windowId: String? = null,
    val windowEligible: Boolean? = null,
    val windowSignals: List<String> = emptyList(),
    val campaignId: String? = null,
    val neighboringCampaignId: String? = null,
    val campaignSilenceId: String? = null,
    val campaignContextReady: Boolean? = null
)

object ScreeningPolicy {
    private val eligible = setOf(
        "NOT_IN_CONTACTS", "STIR_SHAKEN_UNVERIFIED", "PREFIX5_CHIPEIRA_FARM", "PREFIX6_NEIGHBOR_WARNING"
    )

    fun resolve(
        outcome: String,
        rules: List<TriggeredRuleDto>,
        match: PrefixQuarantineMatch,
        window: ExpectedWindow?,
        at: Long,
        silence: CampaignSilence? = null,
        neighboringSilence: CampaignSilence? = null,
        shadowMode: Boolean = false
    ): ScreeningPolicyDecision {
        val active = window?.takeIf { it.activeAt(at) }
        val signals = rules.filter { it.weight > 0 }.map { it.rule_id }
        val canRelease = active != null && outcome == "BLOCKED" && signals.isNotEmpty() && signals.all {
            it in eligible || (it == "PREFIX_QUARANTINE_HIT" && !active.keepExactQuarantine &&
                match == PrefixQuarantineMatch.EXACT8)
        }
        val normal = if (outcome == "BLOCKED") "BLOCK" else "ALLOW"
        val chosen = when {
            canRelease -> "ALLOW"
            normal == "BLOCK" -> "BLOCK"
            outcome != "ALLOWED_LOW_SCORE" -> "ALLOW"
            silence != null && !shadowMode -> "SILENCE"
            else -> "ALLOW"
        }
        val reason = when {
            canRelease -> "EXPECTED_CALL_WINDOW"
            chosen == "SILENCE" -> "CAMPAIGN_SILENCE"
            else -> outcome
        }
        return ScreeningPolicyDecision(
            normal, chosen, reason, active?.id, active?.let { canRelease },
            if (active == null) emptyList() else signals, silence?.campaignId,
            neighboringSilence?.campaignId, silence?.id
        )
    }
}
