package br.com.spamshield.domain

data class NormalizedPhone(
    val rawNumber: String,
    val normalizedNumber: String,
    val nationalDigits: String,
    val ddd: String,
    val prefix7: String,
    val prefix8: String = "",
    val prefix6: String,
    val lineType: String,
    val carrierHint: String? = null,
    val subscriberNumber: String = "",
    val cspExtracted: String? = null,
    val isShortOrUtilityCode: Boolean = false
) {
    /** Eight national digits, or null for short codes and non-geographic numbers. */
    val storedPrefix7: String?
        get() = prefix7.takeIf {
            it.length == 8 && lineType != "TOLL_FREE_OR_SPECIAL" && !isShortOrUtilityCode
        }
}

object PhoneNormalizer {

    private val VALID_DDDS = setOf(
        "11", "12", "13", "14", "15", "16", "17", "18", "19",
        "21", "22", "24", "27", "28",
        "31", "32", "33", "34", "35", "37", "38",
        "41", "42", "43", "44", "45", "46", "47", "48", "49",
        "51", "53", "54", "55",
        "61", "62", "63", "64", "65", "66", "67", "68", "69",
        "71", "73", "74", "75", "77", "79",
        "81", "82", "83", "84", "85", "86", "87", "88", "89",
        "91", "92", "93", "94", "95", "96", "97", "98", "99"
    )

    private val CSP_CODES = setOf("12", "14", "15", "21", "31", "41", "43", "99")
    private val UTILITY_103 = Regex("^103\\d{2}$")
    private val UTILITY_105 = Regex("^105\\d$")
    private val UTILITY_106 = Regex("^106\\d{2}$")
    private val UTILITY_1XX = Regex("^1\\d{2}$")

    fun normalize(rawInput: String, defaultDdd: String = "31"): NormalizedPhone {
        val raw = rawInput.trim()
        val digitsOnly = raw.filter { it.isDigit() }

        if (digitsOnly.isEmpty()) {
            return emptyPhone(raw)
        }

        matchUtility(raw, digitsOnly, defaultDdd)?.let { return it }

        val parsed = parseGeographic(digitsOnly, defaultDdd)
        val lineType = classifyLineType(parsed.subscriber)
        val cluster = if (lineType == "TOLL_FREE_OR_SPECIAL") {
            ""
        } else {
            parsed.national.take(8).takeIf { it.length == 8 }.orEmpty()
        }
        val carrierHint = if (lineType == "MOBILE" || lineType == "MALFORMED_OR_SPOOFED") {
            guessCarrierBySubscriber(parsed.subscriber, if (lineType == "MOBILE") "MOBILE" else "MOBILE", cluster)
        } else {
            guessCarrierBySubscriber(parsed.subscriber, lineType, cluster)
        }

        return NormalizedPhone(
            rawNumber = raw,
            normalizedNumber = "+55${parsed.national}",
            nationalDigits = parsed.national,
            ddd = parsed.ddd,
            prefix7 = cluster,
            prefix8 = cluster,
            prefix6 = if (cluster.length == 8) parsed.national.take(6) else "",
            lineType = lineType,
            carrierHint = carrierHint,
            subscriberNumber = parsed.subscriber,
            cspExtracted = parsed.cspExtracted,
            isShortOrUtilityCode = false
        )
    }

    private fun matchUtility(raw: String, digits: String, defaultDdd: String): NormalizedPhone? {
        if (digits.startsWith("0") && digits.length >= 4) {
            val ddd = digits.substring(1, 3)
            val rest = digits.substring(3)
            if (ddd in VALID_DDDS && rest.length < 8 && isUtilityToken(rest)) {
                return utilityPhone(raw, rest, ddd)
            }
        }
        if (digits.length < 8 || isLongUtility(digits)) {
            return utilityPhone(raw, digits, defaultDdd)
        }
        return null
    }

    private fun isUtilityToken(code: String): Boolean {
        return UTILITY_103.matches(code) ||
            UTILITY_105.matches(code) ||
            UTILITY_106.matches(code) ||
            UTILITY_1XX.matches(code) ||
            code.startsWith("0800") ||
            code.startsWith("0300") ||
            code.startsWith("0303") ||
            code.startsWith("0500") ||
            code.startsWith("400") ||
            code.startsWith("3003") ||
            code.startsWith("4003") ||
            code.startsWith("4004")
    }

    private fun isLongUtility(digits: String): Boolean {
        return digits.startsWith("0800") ||
            digits.startsWith("0300") ||
            digits.startsWith("0303") ||
            digits.startsWith("0500") ||
            digits.startsWith("4004") ||
            digits.startsWith("4003") ||
            digits.startsWith("3003") ||
            (digits.startsWith("400") && digits.length <= 8)
    }

    private fun utilityPhone(raw: String, code: String, ddd: String): NormalizedPhone {
        return NormalizedPhone(
            rawNumber = raw,
            normalizedNumber = code,
            nationalDigits = code,
            ddd = ddd,
            prefix7 = "",
            prefix8 = "",
            prefix6 = "",
            lineType = "TOLL_FREE_OR_SPECIAL",
            carrierHint = utilityCarrier(code),
            subscriberNumber = code,
            cspExtracted = null,
            isShortOrUtilityCode = true
        )
    }

    private fun utilityCarrier(code: String): String {
        val csp = if (code.length >= 5 && code.startsWith("103")) code.substring(3, 5) else null
        return when (csp) {
            "15" -> "Vivo"
            "21", "14" -> "Claro"
            "41" -> "TIM"
            else -> "VoIP"
        }
    }

    private data class GeographicParts(
        val ddd: String,
        val subscriber: String,
        val national: String,
        val cspExtracted: String?
    )

    private fun parseGeographic(digits: String, defaultDdd: String): GeographicParts {
        if (digits.startsWith("00") && digits.length >= 14) {
            val csp = digits.substring(2, 4)
            val ddd = digits.substring(4, 6)
            if (csp in CSP_CODES && ddd in VALID_DDDS) {
                val subscriber = digits.substring(6)
                return GeographicParts(ddd, subscriber, "$ddd$subscriber", "00$csp")
            }
        }
        if (digits.startsWith("0") && digits.length >= 13) {
            val csp = digits.substring(1, 3)
            val ddd = digits.substring(3, 5)
            if (csp in CSP_CODES && ddd in VALID_DDDS) {
                val subscriber = digits.substring(5)
                return GeographicParts(ddd, subscriber, "$ddd$subscriber", "0$csp")
            }
        }
        if (digits.startsWith("0") && digits.length in 11..12) {
            val ddd = digits.substring(1, 3)
            if (ddd in VALID_DDDS) {
                val subscriber = digits.substring(3)
                return GeographicParts(ddd, subscriber, digits.substring(1), null)
            }
        }
        if (digits.startsWith("55") && digits.length >= 12) {
            val ddd = digits.substring(2, 4)
            if (ddd in VALID_DDDS) {
                val subscriber = digits.substring(4)
                return GeographicParts(ddd, subscriber, digits.substring(2), null)
            }
        }
        if (digits.length in 10..11 && digits.take(2) in VALID_DDDS) {
            return GeographicParts(digits.take(2), digits.drop(2), digits, null)
        }
        if (digits.length in 8..9) {
            return GeographicParts(defaultDdd, digits, "$defaultDdd$digits", null)
        }
        return GeographicParts(defaultDdd, digits, digits, null)
    }

    private fun emptyPhone(raw: String) = NormalizedPhone(
        rawNumber = raw,
        normalizedNumber = "",
        nationalDigits = "",
        ddd = "",
        prefix7 = "",
        prefix8 = "",
        prefix6 = "",
        lineType = "UNKNOWN",
        carrierHint = null
    )

    fun isVoipPrefixOrSubscriber(
        subscriber: String,
        prefix7: String = "",
        rawCarrier: String? = null,
        isCorporatePabx: Boolean = false
    ): Boolean {
        if (isCorporatePabx) return true
        val upper = rawCarrier?.uppercase() ?: ""
        if (upper.contains("VOIP") ||
            upper.contains("PABX") ||
            upper.contains("TRONCO") ||
            upper.contains("DATORA") ||
            upper.contains("NVOIP") ||
            upper.contains("AMERICANET") ||
            upper.contains("TVN") ||
            upper.contains("VOXBRAS")
        ) {
            return true
        }
        val sub = if (subscriber.length >= 4) {
            subscriber
        } else if (prefix7.length >= 5 && prefix7.take(2) in VALID_DDDS) {
            prefix7.drop(2)
        } else {
            prefix7
        }
        if (sub.startsWith("030") || sub.startsWith("080") || sub.startsWith("400") || sub.startsWith("300")) {
            return true
        }
        val p3 = sub.take(3)
        return p3 in setOf(
            "210", "211", "212", "251", "252", "253", "255",
            "300", "301", "302", "303", "304", "305", "306", "307", "308", "309",
            "310", "311", "312", "313", "314", "315", "318", "319",
            "400", "402", "406", "410", "411", "413", "414", "485",
            "502", "503", "508", "509"
        )
    }

    private fun guessCarrierBySubscriber(
        subscriber: String,
        lineType: String,
        prefix7: String = ""
    ): String {
        if (lineType == "TOLL_FREE_OR_SPECIAL") return "VoIP"
        if (lineType == "FIXED_OR_VOIP") {
            return if (isVoipPrefixOrSubscriber(subscriber, prefix7)) "VoIP" else "Fixo"
        }
        if (subscriber.length in 8..9 && subscriber.first() in '6'..'9') {
            val normalizedSub = if (subscriber.length == 8 && subscriber.first() != '9') {
                "9$subscriber"
            } else {
                subscriber
            }
            val p3 = if (normalizedSub.first() == '9') normalizedSub.take(3) else subscriber.take(3)
            return when (p3) {
                "910", "920", "971", "972", "976", "981", "982", "983", "985", "986", "991", "992", "993" -> "TIM"
                "933", "937", "938", "939", "973", "974", "975", "980", "984", "987", "988", "989", "994" -> "Claro"
                "979", "995", "996", "997", "998", "999" -> "Vivo"
                else -> "Outros"
            }
        }
        return "Outros"
    }

    fun hasAbnormalTrunkOrCspHeader(rawNumber: String, userDdd: String = "31"): Boolean {
        val trimmed = rawNumber.trim()
        if (!trimmed.startsWith("0")) return false
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        if (digits.startsWith("0300") || digits.startsWith("0303") || digits.startsWith("0800") || digits.startsWith("00")) {
            return true
        }
        if (digits.length < 11) return true
        if (digits.length >= 13) return true
        val ddd = digits.substring(1, 3)
        if (ddd != userDdd) return true
        return digits.length == 11 || digits.getOrNull(3) != '9'
    }

    fun isKnownChipeiraFarmPrefix5(prefix5: String): Boolean {
        if (prefix5.length < 5) return false
        val sub3 = prefix5.substring(2, 5)
        return sub3 in setOf("937", "938", "939", "980", "910")
    }

    fun normalizeCarrierName(
        rawCarrier: String?,
        lineType: String = "MOBILE",
        isCorporatePabx: Boolean = false,
        prefix7: String = ""
    ): String {
        if (lineType == "TOLL_FREE_OR_SPECIAL") return "VoIP"
        if (lineType == "FIXED_OR_VOIP" || isCorporatePabx) {
            return if (isVoipPrefixOrSubscriber("", prefix7, rawCarrier, isCorporatePabx)) "VoIP" else "Fixo"
        }
        val upper = rawCarrier?.uppercase() ?: return "Outros"
        return when {
            upper.contains("VOIP") || upper.contains("PABX") || upper.contains("TRONCO") -> {
                if (prefix7.isNotEmpty() && !isVoipPrefixOrSubscriber("", prefix7, null, false)) "Fixo" else "VoIP"
            }
            upper == "FIXO" -> "Fixo"
            upper.contains("TIM") -> "TIM"
            upper.contains("CLARO") -> "Claro"
            upper.contains("VIVO") -> "Vivo"
            else -> rawCarrier.ifBlank { "Outros" }
        }
    }

    private fun classifyLineType(subscriber: String): String {
        if (subscriber.isEmpty()) return "UNKNOWN"
        val first = subscriber.first()
        val length = subscriber.length
        return when {
            first == '9' && length == 9 -> "MOBILE"
            first == '9' -> "MALFORMED_OR_SPOOFED"
            first in '2'..'5' && length == 8 -> "FIXED_OR_VOIP"
            first in '2'..'5' -> "MALFORMED_OR_SPOOFED"
            first in '6'..'8' -> "MALFORMED_OR_SPOOFED"
            else -> "UNKNOWN"
        }
    }
}
