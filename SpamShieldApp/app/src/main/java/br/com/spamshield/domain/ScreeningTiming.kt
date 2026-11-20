package br.com.spamshield.domain

import kotlinx.serialization.Serializable

@Serializable
data class ScreeningLatency(
    val callbackSetupMs: Double? = null,
    val evaluationWaitMs: Double? = null,
    val localContextMs: Double? = null,
    val rulesMs: Double? = null,
    val policyMs: Double? = null,
    val submissionMs: Double? = null,
    val journalMs: Double? = null,
    val candidateMs: Double? = null,
    val budgetMs: Long? = null,
    val timedOut: Boolean? = null,
    val stageReached: String? = null
)

internal class ScreeningTiming(private val start: Long, private val clock: () -> Long) {
    private val marks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun mark(stage: String) {
        marks[stage] = clock()
    }

    fun elapsed() = (clock() - start) / 1_000_000.0

    fun snapshot(budget: Long, timedOut: Boolean): ScreeningLatency {
        val current = marks.toMap()
        fun duration(from: String, to: String): Double? {
            val end = current[to] ?: return null
            val beginning = current[from] ?: return null
            return (end - beginning) / 1_000_000.0
        }
        return ScreeningLatency(
            callbackSetupMs = current["QUEUED"]?.let { (it - start) / 1_000_000.0 },
            evaluationWaitMs = duration("QUEUED", "EVALUATION"),
            localContextMs = duration("EVALUATION", "CONTEXT_READY"),
            rulesMs = duration("CONTEXT_READY", "RULES_READY"),
            policyMs = duration("RULES_READY", "POLICY_READY"),
            submissionMs = duration("SUBMITTING", "SUBMITTED"),
            budgetMs = budget, timedOut = timedOut,
            stageReached = listOf("POLICY_READY", "RULES_READY", "CONTEXT_READY", "EVALUATION", "QUEUED")
                .firstOrNull { it in current } ?: "CALLBACK"
        )
    }
}
