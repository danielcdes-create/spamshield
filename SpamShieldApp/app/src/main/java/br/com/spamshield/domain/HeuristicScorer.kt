package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlin.math.max

data class CompiledRegexRule(
    val ruleId: String,
    val weight: Int,
    val pattern: String,
    val regex: Regex
)

data class ScoreInput(
    val rawNumber: String,
    val normalizedNumber: String = "",
    val ddd: String = "",
    val prefix7: String? = null,
    val subscriberNumber: String = "",
    val carrierGuess: String? = null,
    val lineType: String = "UNKNOWN",
    val stirShakenStatus: String = "NOT_VERIFIED",
    val inContacts: Boolean = false,
    val quarantineMatch: PrefixQuarantineMatch = PrefixQuarantineMatch.NONE,
    val thresholdApplied: Int = 70,
    val userLocalDdd: String = "31",
    val callDurationSec: Int? = null,
    val historicalUnverified: Boolean = false,
    val cspExtracted: String? = null,
    val isShortOrUtilityCode: Boolean = false,
    val shadowMode: Boolean = false,
    val unverifiedWeight: Int = 20,
    val extraRegexRules: List<CompiledRegexRule> = emptyList()
)

data class ScoreResult(
    val finalScore: Int,
    val outcome: String,
    val category: String,
    val triggeredRules: List<TriggeredRuleDto>
)

object HeuristicScorer {
    val VOIP_TRUNK_GROUP = setOf(
        "REGEX_MG_VOIP_TRUNKS",
        "BLACKLIST_MG_VOIP_PABX",
        "ANATEL_PABX_VOIP_RANGE"
    )

    private val CSP_CODES = setOf("12", "14", "15", "21", "31", "41", "43", "99")

    fun score(input: ScoreInput): ScoreResult {
        if (input.inContacts) {
            val rules = listOf(TriggeredRuleDto("CONTACT_WHITELIST", -1000, null))
            return ScoreResult(0, "ALLOWED_CONTACT", "LEGITIMATE", rules)
        }

        val rules = mutableListOf<TriggeredRuleDto>()
        rules.add(TriggeredRuleDto("NOT_IN_CONTACTS", 25, null))

        when (input.stirShakenStatus) {
            "FAILED" -> rules.add(
                TriggeredRuleDto("STIR_SHAKEN_FAILED", 85, "VERIFICATION_STATUS_FAILED")
            )
            "", "NOT_VERIFIED" -> {
                if (input.historicalUnverified) {
                    rules.add(TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", 15, "Histórico"))
                } else {
                    rules.add(
                        TriggeredRuleDto(
                            "STIR_SHAKEN_UNVERIFIED",
                            input.unverifiedWeight,
                            "VERIFICATION_STATUS_NOT_VERIFIED"
                        )
                    )
                }
            }
        }

        if (input.lineType == "MALFORMED_OR_SPOOFED") {
            val length = input.subscriberNumber.length
            val meta = if (input.subscriberNumber.startsWith("9")) {
                "Mobile length $length != 9"
            } else {
                "Digit ${input.subscriberNumber.firstOrNull() ?: '?'} length $length is not a mobile subscriber"
            }
            rules.add(TriggeredRuleDto("MALFORMED_NUMBER_SPOOFING", 85, meta))
        }

        when (input.quarantineMatch) {
            PrefixQuarantineMatch.EXACT8 -> rules.add(
                TriggeredRuleDto(
                    "PREFIX_QUARANTINE_HIT",
                    QuarantinePrefixIndex.EXACT_WEIGHT,
                    input.prefix7
                )
            )
            PrefixQuarantineMatch.BROAD7 -> rules.add(
                TriggeredRuleDto(
                    "PREFIX7_BROAD_QUARANTINE_HIT",
                    QuarantinePrefixIndex.BROAD_WEIGHT,
                    input.prefix7?.take(7)
                )
            )
            PrefixQuarantineMatch.NONE -> Unit
        }

        val texts = listOf(input.rawNumber, input.normalizedNumber, input.subscriberNumber, input.prefix7.orEmpty())
            .filter { it.isNotEmpty() }
        val voipCandidates = mutableListOf<TriggeredRuleDto>()
        val seenVoip = mutableSetOf<String>()

        fun considerVoip(ruleId: String, weight: Int, pattern: String, regex: Regex) {
            if (ruleId in seenVoip) return
            if (texts.any { regex.containsMatchIn(it) || regex.matches(it) }) {
                seenVoip.add(ruleId)
                voipCandidates.add(TriggeredRuleDto(ruleId, weight, pattern))
            }
        }

        input.extraRegexRules.forEach { rule ->
            if (rule.ruleId in VOIP_TRUNK_GROUP) {
                considerVoip(rule.ruleId, rule.weight, rule.pattern, rule.regex)
            } else if (texts.any { rule.regex.matches(it) }) {
                rules.add(TriggeredRuleDto(rule.ruleId, rule.weight, rule.pattern))
            }
        }
        considerVoip(
            "BLACKLIST_MG_VOIP_PABX",
            80,
            ScreeningPatternCache.BLACKLIST_MG_VOIP_PABX_PATTERN,
            ScreeningPatternCache.blacklistMgVoipPabx()
        )
        considerVoip(
            "REGEX_MG_VOIP_TRUNKS",
            80,
            ScreeningPatternCache.REGEX_MG_VOIP_TRUNKS_PATTERN,
            ScreeningPatternCache.regexMgVoipTrunks()
        )
        if (shouldApplyAnatel(input)) {
            voipCandidates.add(TriggeredRuleDto("ANATEL_PABX_VOIP_RANGE", 35, input.carrierGuess))
        }
        pickVoipWinner(voipCandidates)?.let { rules.add(it) }

        if (shouldApplyLeadingZero(input)) {
            rules.add(
                TriggeredRuleDto(
                    "LEADING_ZERO_TRUNK_DIALING",
                    25,
                    input.rawNumber.trim().take(4)
                )
            )
        }

        durationRule(input.callDurationSec)?.let { rules.add(it) }

        return finalize(rules, input)
    }

    fun durationRule(callDurationSec: Int?): TriggeredRuleDto? {
        val seconds = callDurationSec ?: return null
        return when (seconds) {
            in 1..5 -> TriggeredRuleDto("PING_CALL_PENALTY", 55, "Duração ${seconds}s")
            in 6..11 -> TriggeredRuleDto(
                "SHORT_ROBOCALL_PENALTY",
                30,
                "Desligamento rápido de robocall 6s-11s"
            )
            in 12..Int.MAX_VALUE -> TriggeredRuleDto(
                "CONVERSATION_ANSWERED_BONUS",
                -40,
                "Conversa atendida >= 12s"
            )
            else -> null
        }
    }

    fun shouldApplyAnatel(input: ScoreInput): Boolean {
        if (input.isShortOrUtilityCode || input.lineType == "TOLL_FREE_OR_SPECIAL") return false
        if (input.carrierGuess == "VoIP") return true
        return subscriberInDedicatedVoipBlock(input.subscriberNumber)
    }

    fun subscriberInDedicatedVoipBlock(subscriber: String): Boolean {
        if (subscriber.startsWith("2108") || subscriber.startsWith("2517")) return true
        return subscriber.take(2) in setOf("30", "31", "40", "41")
    }

    fun shouldApplyLeadingZero(input: ScoreInput): Boolean {
        if (input.cspExtracted != null || hasExplicitCsp(input.rawNumber)) return true
        val digits = input.rawNumber.filter { it.isDigit() }
        if (digits.startsWith("055")) return true
        if (input.stirShakenStatus == "FAILED") return true
        if (input.quarantineMatch == PrefixQuarantineMatch.EXACT8) return true
        if (input.carrierGuess == "VoIP") return true
        return false
    }

    fun hasExplicitCsp(rawNumber: String): Boolean {
        val digits = rawNumber.filter { it.isDigit() }
        if (digits.startsWith("055")) return true
        if (digits.startsWith("00") && digits.length >= 14) {
            return digits.substring(2, 4) in CSP_CODES
        }
        if (digits.startsWith("0") && digits.length >= 13) {
            return digits.substring(1, 3) in CSP_CODES
        }
        return false
    }

    fun equation(rules: List<TriggeredRuleDto>): Int = max(0, rules.sumOf { it.weight })

    fun resolveOutcome(score: Int, threshold: Int, callDurationSec: Int?, shadowMode: Boolean): String {
        if (callDurationSec != null && callDurationSec > 0 && score >= threshold) {
            return "FLAGGED_POST_CALL_QUARANTINED"
        }
        if (score >= threshold && shadowMode) return "SHADOW_BLOCKED"
        if (score >= threshold) return "BLOCKED"
        return "ALLOWED_LOW_SCORE"
    }

    fun resolveCategory(
        stirShakenStatus: String,
        outcome: String,
        rules: List<TriggeredRuleDto>
    ): String {
        if (rules.any { it.rule_id == "USER_CONFIRMED_SPAM_OVERRIDE" }) return "USER_REPORTED_SPAM"
        if (stirShakenStatus == "FAILED" && (outcome == "BLOCKED" || outcome == "SHADOW_BLOCKED")) {
            return "SPOOFING_STIR_FAILED"
        }
        if (rules.any { it.rule_id == "PING_CALL_PENALTY" }) return "PING_CALL_ROBOCALL"
        if (stirShakenStatus == "PASSED" && outcome == "ALLOWED_LOW_SCORE") return "VERIFIED_UNKNOWN"
        if (outcome == "BLOCKED" || outcome == "SHADOW_BLOCKED" || outcome == "FLAGGED_POST_CALL_QUARANTINED") {
            return "ROBOCALL_PREFIX_CLUSTER"
        }
        return "UNKNOWN_UNVERIFIED"
    }

    fun finalize(rules: List<TriggeredRuleDto>, input: ScoreInput): ScoreResult {
        val score = equation(rules)
        val outcome = resolveOutcome(score, input.thresholdApplied, input.callDurationSec, input.shadowMode)
        val category = resolveCategory(input.stirShakenStatus, outcome, rules)
        return ScoreResult(score, outcome, category, rules)
    }

    private fun pickVoipWinner(candidates: List<TriggeredRuleDto>): TriggeredRuleDto? {
        if (candidates.isEmpty()) return null
        return candidates.maxWith(
            compareBy<TriggeredRuleDto> { it.weight }.thenBy { voipTieBreak(it.rule_id) }
        )
    }

    private fun voipTieBreak(ruleId: String): Int = when (ruleId) {
        "BLACKLIST_MG_VOIP_PABX" -> 3
        "REGEX_MG_VOIP_TRUNKS" -> 2
        "ANATEL_PABX_VOIP_RANGE" -> 1
        else -> 0
    }
}
