package br.com.spamshield.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Compiles each screening regular expression once for the process lifetime.
 * Call evaluation must obtain patterns from here and must not call `Regex(...)`.
 */
object ScreeningPatternCache {
    const val BLACKLIST_MG_VOIP_PABX_PATTERN =
        """^(?:\+55|0(?:\d{2})?)?31(?:2108|2517|3191|4020|4062|4101)\d{4}$"""

    const val REGEX_MG_VOIP_TRUNKS_PATTERN =
        """^(?:\+55|0)?314101\d{4}$"""

    private val compiled = ConcurrentHashMap<String, Regex>()

    init {
        pattern(BLACKLIST_MG_VOIP_PABX_PATTERN)
        pattern(REGEX_MG_VOIP_TRUNKS_PATTERN)
    }

    fun pattern(source: String): Regex = compiled.computeIfAbsent(source) { Regex(it) }

    fun blacklistMgVoipPabx(): Regex = pattern(BLACKLIST_MG_VOIP_PABX_PATTERN)

    fun regexMgVoipTrunks(): Regex = pattern(REGEX_MG_VOIP_TRUNKS_PATTERN)
}
