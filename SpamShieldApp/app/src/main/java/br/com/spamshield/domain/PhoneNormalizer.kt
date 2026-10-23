package br.com.spamshield.domain

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

data class NormalizedPhone(
    val rawNumber: String,
    val normalizedNumber: String, // E.164 format e.g. +553141011230
    val nationalDigits: String,   // e.g. 3141011230
    val ddd: String,              // e.g. 31
    val prefix7: String,          // e.g. 3141011 or 3198621
    val prefix6: String,          // e.g. 314101 or 319862
    val lineType: String,         // MOBILE | FIXED_OR_VOIP | TOLL_FREE_OR_SPECIAL | UNKNOWN
    val carrierHint: String? = null // TIM | Claro | Vivo | VoIP / Fixo | Outros
)

object PhoneNormalizer {

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

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

    fun normalize(rawInput: String, defaultDdd: String = "31"): NormalizedPhone {
        val raw = rawInput.trim()
        val digitsOnly = raw.filter { it.isDigit() }

        if (digitsOnly.isEmpty()) {
            return NormalizedPhone(
                rawNumber = raw,
                normalizedNumber = "",
                nationalDigits = "",
                ddd = "",
                prefix7 = "",
                prefix6 = "",
                lineType = "UNKNOWN",
                carrierHint = null
            )
        }

        // Check special non-geographic prefixes first (0300, 0303, 0800, 0500, 4004, 3003, 4003)
        if (digitsOnly.startsWith("0300") ||
            digitsOnly.startsWith("0303") ||
            digitsOnly.startsWith("0800") ||
            digitsOnly.startsWith("0500") ||
            digitsOnly.startsWith("4004") ||
            digitsOnly.startsWith("3003") ||
            digitsOnly.startsWith("4003")
        ) {
            val cleanSpecial = digitsOnly
            val p7 = cleanSpecial.take(7)
            val p6 = cleanSpecial.take(6)
            return NormalizedPhone(
                rawNumber = raw,
                normalizedNumber = "+55${cleanSpecial.removePrefix("0")}",
                nationalDigits = cleanSpecial,
                ddd = "00",
                prefix7 = p7,
                prefix6 = p6,
                lineType = "TOLL_FREE_OR_SPECIAL",
                carrierHint = "VoIP"
            )
        }

        // Check short SIP codes or carrier telemarketing codes (e.g. 005, 10315, 03110315)
        if (digitsOnly.length < 8 ||
            (digitsOnly.length == 8 && digitsOnly.startsWith("0") && digitsOnly.substring(1, 3) in VALID_DDDS && digitsOnly.substring(3).startsWith("10"))
        ) {
            val cleanCode = if (digitsOnly.length == 8 && digitsOnly.startsWith("0")) {
                digitsOnly.substring(1) // e.g. 3110315
            } else {
                "$defaultDdd$digitsOnly"
            }
            return NormalizedPhone(
                rawNumber = raw,
                normalizedNumber = "+55$cleanCode",
                nationalDigits = cleanCode,
                ddd = defaultDdd,
                prefix7 = cleanCode.take(7),
                prefix6 = cleanCode.take(6),
                lineType = "TOLL_FREE_OR_SPECIAL",
                carrierHint = "VoIP"
            )
        }

        val cspCarrier = extractCspCarrier(digitsOnly)
        val national = extractBrazilianNationalDigits(digitsOnly, defaultDdd)

        val ddd = if (national.length >= 10 && national.take(2) in VALID_DDDS) {
            national.take(2)
        } else {
            defaultDdd
        }

        val subscriber = if (national.length >= 10 && national.startsWith(ddd)) {
            national.substring(2)
        } else {
            national
        }

        val fullNational = "$ddd$subscriber"

        val e164 = try {
            val parsed = phoneUtil.parse("+55$fullNational", "BR")
            if (phoneUtil.isPossibleNumber(parsed)) {
                phoneUtil.format(parsed, PhoneNumberFormat.E164)
            } else {
                "+55$fullNational"
            }
        } catch (_: Exception) {
            "+55$fullNational"
        }

        val prefix7 = fullNational.take(7)
        val prefix6 = fullNational.take(6)
        val lineType = classifyLineType(subscriber)
        val carrierHint = if (lineType == "MOBILE") {
            cspCarrier ?: guessCarrierBySubscriber(subscriber, lineType, prefix7)
        } else {
            guessCarrierBySubscriber(subscriber, lineType, prefix7)
        }

        return NormalizedPhone(
            rawNumber = raw,
            normalizedNumber = e164,
            nationalDigits = fullNational,
            ddd = ddd,
            prefix7 = prefix7,
            prefix6 = prefix6,
            lineType = lineType,
            carrierHint = carrierHint
        )
    }

    private fun extractCspCarrier(digits: String): String? {
        if (digits.startsWith("00") && digits.length in 14..15) {
            val csp = digits.substring(2, 4)
            val candidateDdd = digits.substring(4, 6)
            if (candidateDdd in VALID_DDDS) {
                return when (csp) {
                    "41" -> "TIM"
                    "21" -> "Claro"
                    "15" -> "Vivo"
                    else -> null
                }
            }
        }
        if (digits.startsWith("0") && digits.length in 13..14) {
            val csp = digits.substring(1, 3)
            val candidateDdd = digits.substring(3, 5)
            if (candidateDdd in VALID_DDDS) {
                return when (csp) {
                    "41" -> "TIM"
                    "21" -> "Claro"
                    "15" -> "Vivo"
                    else -> null
                }
            }
        }
        return null
    }

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
        if (lineType == "TOLL_FREE_OR_SPECIAL") {
            return "VoIP"
        }
        if (lineType == "FIXED_OR_VOIP") {
            return if (isVoipPrefixOrSubscriber(subscriber, prefix7)) "VoIP" else "Fixo"
        }
        if (subscriber.length in 8..9 && subscriber.first() in '6'..'9') {
            val normalizedSub = if (subscriber.length == 8) "9$subscriber" else subscriber
            val p3 = normalizedSub.take(3) // e.g. 987, 986, 976, 999
            return when (p3) {
                "910", "920", "971", "972", "976", "981", "982", "983", "985", "986", "991", "992", "993" -> "TIM"
                "933", "937", "938", "939", "973", "974", "975", "980", "984", "987", "988", "989", "994" -> "Claro"
                "979", "995", "996", "997", "998", "999" -> "Vivo"
                else -> "Outros"
            }
        }
        return "Outros"
    }

    fun normalizeCarrierName(
        rawCarrier: String?,
        lineType: String = "MOBILE",
        isCorporatePabx: Boolean = false,
        prefix7: String = ""
    ): String {
        if (lineType == "TOLL_FREE_OR_SPECIAL") {
            return "VoIP"
        }
        if (lineType == "FIXED_OR_VOIP" || isCorporatePabx) {
            return if (isVoipPrefixOrSubscriber("", prefix7, rawCarrier, isCorporatePabx)) {
                "VoIP"
            } else {
                "Fixo"
            }
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

    private fun extractBrazilianNationalDigits(digits: String, defaultDdd: String): String {
        val working = digits

        if (working.startsWith("00") && working.length in 14..15) {
            val candidateDdd = working.substring(4, 6)
            if (candidateDdd in VALID_DDDS) {
                return working.substring(4)
            }
        }

        if (working.startsWith("55") && working.length in 12..13) {
            val candidateDdd = working.substring(2, 4)
            if (candidateDdd in VALID_DDDS) {
                return working.substring(2)
            }
        }

        if (working.startsWith("0") && working.length in 13..14) {
            val candidateDdd = working.substring(3, 5)
            if (candidateDdd in VALID_DDDS) {
                return working.substring(3)
            }
        }

        if (working.startsWith("0") && working.length in 11..12) {
            val candidateDdd = working.substring(1, 3)
            if (candidateDdd in VALID_DDDS) {
                return working.substring(1)
            }
        }

        if (working.length in 10..11 && working.take(2) in VALID_DDDS) {
            return working
        }

        if (working.length in 8..9) {
            return "$defaultDdd$working"
        }

        return working
    }

    private fun classifyLineType(subscriber: String): String {
        if (subscriber.isEmpty()) return "UNKNOWN"
        val firstChar = subscriber.first()
        return when {
            subscriber.length == 9 && firstChar == '9' -> "MOBILE"
            subscriber.length == 8 && (firstChar in '6'..'9') -> "MOBILE"
            subscriber.length == 8 && (firstChar in '2'..'5') -> "FIXED_OR_VOIP"
            else -> "UNKNOWN"
        }
    }
}
