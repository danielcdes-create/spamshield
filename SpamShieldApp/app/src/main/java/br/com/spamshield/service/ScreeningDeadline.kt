package br.com.spamshield.service

import java.util.concurrent.atomic.AtomicBoolean

internal class ScreeningDeadline(
    private val startedAtNanos: Long,
    private val elapsedRealtimeNanos: () -> Long,
    private val budgetMs: Long = BUDGET_MS
) {
    private val claimed = AtomicBoolean(false)

    data class Claim(val timedOut: Boolean, val elapsedMs: Double)

    fun claim(): Claim? {
        if (!claimed.compareAndSet(false, true)) return null
        val elapsedMs = (elapsedRealtimeNanos() - startedAtNanos) / 1_000_000.0
        return Claim(timedOut = elapsedMs >= budgetMs, elapsedMs = elapsedMs)
    }

    companion object {
        const val BUDGET_MS = 3500L
    }
}
