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

        // Check special non-geographic prefixes first (0300, 0303, 0800, 0500, 4004, 3003)
        if (digitsOnly.startsWith("0300") ||
            digitsOnly.startsWith("0303") ||
            digitsOnly.startsWith("0800") ||
            digitsOnly.startsWith("0500") ||
            digitsOnly.startsWith("4004") ||
            digitsOnly.startsWith("3003")
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
                carrierHint = "VoIP / Fixo"
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
        val carrierHint = cspCarrier ?: guessCarrierBySubscriber(subscriber, lineType)

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

    private fun guessCarrierBySubscriber(subscriber: String, lineType: String): String {
        if (lineType == "FIXED_OR_VOIP" || lineType == "TOLL_FREE_OR_SPECIAL") {
            return "VoIP / Fixo"
        }
        if (subscriber.length in 8..9 && subscriber.first() in '6'..'9') {
            val normalizedSub = if (subscriber.length == 8) "9$subscriber" else subscriber
            val p3 = normalizedSub.take(3) // e.g. 987, 986, 976, 999
            return when (p3) {
                "971", "972", "976", "981", "982", "983", "985", "986", "991", "992", "993", "910" -> "TIM"
                "973", "974", "975", "984", "987", "988", "989", "994", "933" -> "Claro"
                "995", "996", "997", "998", "999", "979" -> "Vivo"
                else -> "Outros"
            }
        }
        return "Outros"
    }

    fun normalizeCarrierName(
        rawCarrier: String?,
        lineType: String = "MOBILE",
        isCorporatePabx: Boolean = false
    ): String {
        if (isCorporatePabx || lineType == "FIXED_OR_VOIP" || lineType == "TOLL_FREE_OR_SPECIAL") {
            return "VoIP / Fixo"
        }
        val upper = rawCarrier?.uppercase() ?: return "Outros"
        return when {
            upper.contains("VOIP") || upper.contains("FIXO") || upper.contains("PABX") -> "VoIP / Fixo"
            upper.contains("TIM") -> "TIM"
            upper.contains("CLARO") -> "Claro"
            upper.contains("VIVO") -> "Vivo"
            else -> rawCarrier.ifBlank { "Outros" }
        }
    }

    private fun extractBrazilianNationalDigits(digits: String, defaultDdd: String): String {
        val working = digits

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
