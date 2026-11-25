package br.com.spamshield.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `normalizes 03141011230 removing leading zero and extracting DDD 31 and prefix7 3141011`() {
        val result = PhoneNormalizer.normalize("03141011230")
        assertEquals("+553141011230", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("31410112", result.prefix7)
        assertEquals("314101", result.prefix6)
        assertEquals("FIXED_OR_VOIP", result.lineType)
    }

    @Test
    fun `normalizes +553198621040 preserving E164 and extracting prefix7 3198621`() {
        val result = PhoneNormalizer.normalize("+553198621040")
        assertEquals("+553198621040", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("31986210", result.prefix7)
        assertEquals("319862", result.prefix6)
        assertEquals("MALFORMED_OR_SPOOFED", result.lineType)
    }

    @Test
    fun `normalizes 31983211420 11-digit mobile number to E164 and prefix7 3198321`() {
        val result = PhoneNormalizer.normalize("31983211420")
        assertEquals("+5531983211420", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("31983211", result.prefix7)
        assertEquals("319832", result.prefix6)
        assertEquals("MOBILE", result.lineType)
    }

    @Test
    fun `normalizes 0153141011230 with long distance CSP 15`() {
        val result = PhoneNormalizer.normalize("0153141011230")
        assertEquals("+553141011230", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("31410112", result.prefix7)
        assertEquals("314101", result.prefix6)
        assertEquals("FIXED_OR_VOIP", result.lineType)
    }

    @Test
    fun `normalizes 03198621040 removing long-distance zero`() {
        val result = PhoneNormalizer.normalize("03198621040")
        assertEquals("+553198621040", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("31986210", result.prefix7)
        assertEquals("319862", result.prefix6)
    }

    @Test
    fun `classifies 0303 and 4004 as TOLL_FREE_OR_SPECIAL and separates VoIP from Fixo`() {
        val r0303 = PhoneNormalizer.normalize("03031234567")
        assertEquals("TOLL_FREE_OR_SPECIAL", r0303.lineType)
        assertEquals("VoIP", r0303.carrierHint)

        val r4004 = PhoneNormalizer.normalize("40041234")
        assertEquals("TOLL_FREE_OR_SPECIAL", r4004.lineType)
        assertEquals("VoIP", r4004.carrierHint)

        val r0411140043535 = PhoneNormalizer.normalize("0411140043535")
        assertEquals("+551140043535", r0411140043535.normalizedNumber)
        assertEquals("FIXED_OR_VOIP", r0411140043535.lineType)
        assertEquals("VoIP", r0411140043535.carrierHint)

        val rFixo = PhoneNormalizer.normalize("03132873275")
        assertEquals("+553132873275", rFixo.normalizedNumber)
        assertEquals("FIXED_OR_VOIP", rFixo.lineType)
        assertEquals("Fixo", rFixo.carrierHint)
    }

    @Test
    fun `handles empty input, emergency short codes, 0800-0500-3003-105-106 utilities, 0055 header and custom local DDD`() {
        val empty = PhoneNormalizer.normalize("   ---  ")
        assertEquals("", empty.normalizedNumber)
        assertEquals("UNKNOWN", empty.lineType)
        assertEquals(null, empty.storedPrefix7)

        val emergency190 = PhoneNormalizer.normalize("190", "11")
        assertEquals("190", emergency190.normalizedNumber)
        assertEquals("11", emergency190.ddd)
        assertEquals("TOLL_FREE_OR_SPECIAL", emergency190.lineType)
        assertTrue(emergency190.isShortOrUtilityCode)

        for ((raw, expectedNorm, expectedCarrier) in listOf(
            Triple("08007221234", "08007221234", "VoIP"),
            Triple("05001234567", "05001234567", "VoIP"),
            Triple("30031234", "30031234", "VoIP"),
            Triple("40031234", "40031234", "VoIP"),
            Triple("0111058", "1058", "VoIP"),
            Triple("02110621", "10621", "VoIP"),
            Triple("10321", "10321", "Claro"),
            Triple("10341", "10341", "TIM")
        )) {
            val u = PhoneNormalizer.normalize(raw, "11")
            assertEquals(expectedNorm, u.normalizedNumber)
            assertEquals("TOLL_FREE_OR_SPECIAL", u.lineType)
            assertEquals(expectedCarrier, u.carrierHint)
            assertEquals(null, u.storedPrefix7)
        }

        val c0055 = PhoneNormalizer.normalize("005521998765432", "31")
        assertEquals("+5521998765432", c0055.normalizedNumber)
        assertEquals("21", c0055.ddd)
        assertEquals("21998765", c0055.prefix7)
        assertEquals("Vivo", c0055.carrierHint)
        assertEquals("0055", c0055.cspExtracted)

        val localMobileSp = PhoneNormalizer.normalize("987654321", "11")
        assertEquals("+5511987654321", localMobileSp.normalizedNumber)
        assertEquals("11", localMobileSp.ddd)
        assertEquals("11987654", localMobileSp.prefix7)

        val localLandlineSp = PhoneNormalizer.normalize("32873275", "11")
        assertEquals("+551132873275", localLandlineSp.normalizedNumber)
        assertEquals("11", localLandlineSp.ddd)
        assertEquals("Fixo", localLandlineSp.carrierHint)

        assertTrue(PhoneNormalizer.isKnownChipeiraFarmPrefix5("31937"))
        assertTrue(PhoneNormalizer.isKnownChipeiraFarmPrefix5("11910"))
        assertEquals(false, PhoneNormalizer.isKnownChipeiraFarmPrefix5("31999"))
        assertEquals(false, PhoneNormalizer.hasAbnormalTrunkOrCspHeader("011987654321", "11"))
        assertTrue(PhoneNormalizer.hasAbnormalTrunkOrCspHeader("031987654321", "11"))
        assertTrue(PhoneNormalizer.hasAbnormalTrunkOrCspHeader("01511987654321", "11"))
    }

    @Test
    fun `normalizes and infers carriers for real call history numbers from S23 Ultra`() {
        val c1 = PhoneNormalizer.normalize("031987806827")
        assertEquals("+5531987806827", c1.normalizedNumber)
        assertEquals("31987806", c1.prefix7)
        assertEquals("Claro", c1.carrierHint)

        val c2 = PhoneNormalizer.normalize("04111910862316")
        assertEquals("+5511910862316", c2.normalizedNumber)
        assertEquals("11", c2.ddd)
        assertEquals("11910862", c2.prefix7)
        assertEquals("TIM", c2.carrierHint)

        val c3 = PhoneNormalizer.normalize("031976199037")
        assertEquals("+5531976199037", c3.normalizedNumber)
        assertEquals("31976199", c3.prefix7)
        assertEquals("TIM", c3.carrierHint)

        val c4 = PhoneNormalizer.normalize("031986333446")
        assertEquals("+5531986333446", c4.normalizedNumber)
        assertEquals("31986333", c4.prefix7)
        assertEquals("TIM", c4.carrierHint)

        val c5 = PhoneNormalizer.normalize("031939321258")
        assertEquals("+5531939321258", c5.normalizedNumber)
        assertEquals("31939321", c5.prefix7)
        assertEquals("Claro", c5.carrierHint)
    }

    @Test
    fun `canonicalizes carrier names consistently across mobile and fixed prefixes`() {
        assertEquals("Claro", PhoneNormalizer.normalizeCarrierName("CLARO", "MOBILE", false))
        assertEquals("VoIP", PhoneNormalizer.normalizeCarrierName("CLARO / EMBRATEL (VOIP)", "FIXED_OR_VOIP", true, "3141011"))
        assertEquals("Fixo", PhoneNormalizer.normalizeCarrierName("Fixo", "FIXED_OR_VOIP", false, "3132873"))
        assertEquals("TIM", PhoneNormalizer.normalizeCarrierName("TIM S.A.", "MOBILE", false))
        assertEquals("Vivo", PhoneNormalizer.normalizeCarrierName("VIVO", "MOBILE", false))
        assertEquals("VoIP", PhoneNormalizer.normalizeCarrierName("ALGAR / VOIP MG", "FIXED_OR_VOIP", true, "3131920"))
    }

    @Test
    fun `normalizes 055 country header numbers 05531973350693 and 05531975474366 to E164 and 8-digit prefix`() {
        val c1 = PhoneNormalizer.normalize("05531973350693")
        assertEquals("+5531973350693", c1.normalizedNumber)
        assertEquals("31", c1.ddd)
        assertEquals("31973350", c1.prefix7)
        assertEquals("319733", c1.prefix6)
        assertEquals("MOBILE", c1.lineType)
        assertEquals("Claro", c1.carrierHint)
        assertEquals("055", c1.cspExtracted)

        val c2 = PhoneNormalizer.normalize("05531975474366")
        assertEquals("+5531975474366", c2.normalizedNumber)
        assertEquals("31", c2.ddd)
        assertEquals("31975474", c2.prefix7)
        assertEquals("MOBILE", c2.lineType)
        assertEquals("Claro", c2.carrierHint)
        assertEquals("055", c2.cspExtracted)
    }
}
