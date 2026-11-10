package br.com.spamshield.domain

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
    val size: Int get() = exact8.size + broad7.size

    private val exact8 = ConcurrentHashMap.newKeySet<String>()
    private val broad7 = ConcurrentHashMap.newKeySet<String>()

    fun clear() {
        isReady = false
        exact8.clear()
        broad7.clear()
    }

    fun replaceAll(prefixes: Collection<String>) {
        clear()
        prefixes.forEach { add(it) }
        isReady = true
    }

    fun add(prefix: String) {
        when (prefix.length) {
            8 -> if (prefix.all { it.isDigit() }) exact8.add(prefix)
            7 -> if (prefix.all { it.isDigit() }) broad7.add(prefix)
        }
    }

    fun remove(prefix: String) {
        exact8.remove(prefix)
        broad7.remove(prefix)
    }

    fun match(prefix8: String?): PrefixQuarantineMatch {
        if (prefix8 == null || prefix8.length != 8) return PrefixQuarantineMatch.NONE
        if (exact8.contains(prefix8)) return PrefixQuarantineMatch.EXACT8
        if (broad7.contains(prefix8.take(7))) return PrefixQuarantineMatch.BROAD7
        return PrefixQuarantineMatch.NONE
    }

    fun countWithPrefix(prefix: String): Int {
        if (prefix.isEmpty()) return 0
        return exact8.count { it.startsWith(prefix) } + broad7.count { it.startsWith(prefix) }
    }
}
