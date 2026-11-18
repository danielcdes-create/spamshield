package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import java.util.concurrent.ConcurrentHashMap

enum class PrefixQuarantineMatch {
    NONE,
    EXACT8,
    BROAD7
}

/**
 * In-memory quarantine set for the screening path.
 * Eight-digit prefixes are exact hits. A stored 7-digit prefix is only a broad fallback.
 *
 * [BROAD_WEIGHT] is the fixed weight of `PREFIX7_BROAD_QUARANTINE_HIT`.
 * It does not change `PREFIX_QUARANTINE_HIT`, which stays at [EXACT_WEIGHT].
 */
object QuarantinePrefixIndex {
    const val EXACT_WEIGHT = 70
    const val BROAD_WEIGHT = 60

    @Volatile var isReady: Boolean = false
        private set
    val size: Int get() = activeSize(System.currentTimeMillis())

    // Swap a complete snapshot atomically: a refresh must not expose a temporarily empty set.
    @Volatile private var expiresByPrefix = ConcurrentHashMap<String, Long>()
    private val mutationLock = Any()
    private var generation = 0L

    fun clear(): Unit = synchronized(mutationLock) {
        isReady = false
        expiresByPrefix = ConcurrentHashMap()
        generation++
    }

    fun replaceAll(prefixes: Collection<String>): Unit = synchronized(mutationLock) {
        expiresByPrefix = ConcurrentHashMap<String, Long>().apply {
            prefixes.filter(::isValidPrefix).forEach { put(it, Long.MAX_VALUE) }
        }
        isReady = true
        generation++
    }

    fun replaceActive(items: Collection<PrefixQuarantineEntity>, now: Long = System.currentTimeMillis()): Unit = synchronized(mutationLock) {
        expiresByPrefix = activeSnapshot(items, now)
        isReady = true
        generation++
    }

    /** Retry a database snapshot if a call/feedback changed the index while it was being read. */
    suspend fun refreshActive(
        now: Long = System.currentTimeMillis(),
        load: suspend () -> Collection<PrefixQuarantineEntity>
    ) {
        while (true) {
            val beforeRead = synchronized(mutationLock) { generation }
            val snapshot = activeSnapshot(load(), now)
            val applied = synchronized(mutationLock) {
                if (generation != beforeRead) false else {
                    expiresByPrefix = snapshot
                    isReady = true
                    generation++
                    true
                }
            }
            if (applied) return
        }
    }

    fun add(prefix: String, expiresAt: Long = Long.MAX_VALUE): Unit = synchronized(mutationLock) {
        if (isValidPrefix(prefix)) {
            expiresByPrefix[prefix] = expiresAt
            generation++
        }
    }

    fun remove(prefix: String): Unit = synchronized(mutationLock) {
        expiresByPrefix.remove(prefix)
        generation++
    }

    fun match(prefix8: String?, now: Long = System.currentTimeMillis()): PrefixQuarantineMatch {
        if (prefix8 == null || prefix8.length != 8) return PrefixQuarantineMatch.NONE
        val snapshot = expiresByPrefix
        if ((snapshot[prefix8] ?: Long.MIN_VALUE) > now) return PrefixQuarantineMatch.EXACT8
        if ((snapshot[prefix8.take(7)] ?: Long.MIN_VALUE) > now) return PrefixQuarantineMatch.BROAD7
        return PrefixQuarantineMatch.NONE
    }

    fun countWithPrefix(prefix: String, now: Long = System.currentTimeMillis()): Int {
        if (prefix.isEmpty()) return 0
        return expiresByPrefix.count { (key, expiry) -> key.startsWith(prefix) && expiry > now }
    }

    fun activeSize(now: Long): Int = expiresByPrefix.count { it.value > now }

    private fun activeSnapshot(items: Collection<PrefixQuarantineEntity>, now: Long) =
        ConcurrentHashMap<String, Long>().apply {
            items.filter { isValidPrefix(it.prefix7) && it.expiresAt > now }
                .forEach { put(it.prefix7, it.expiresAt) }
        }

    private fun isValidPrefix(prefix: String) = prefix.length in 7..8 && prefix.all { it.isDigit() }
}
