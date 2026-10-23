package br.com.spamshield.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `normalizes 03141011230 removing leading zero and extracting DDD 31 and prefix7 3141011`() {
        val result = PhoneNormalizer.normalize("03141011230")
        assertEquals("+553141011230", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("3141011", result.prefix7)
        assertEquals("314101", result.prefix6)
        assertEquals("FIXED_OR_VOIP", result.lineType)
    }

    @Test
    fun `normalizes +553198621040 preserving E164 and extracting prefix7 3198621`() {
        val result = PhoneNormalizer.normalize("+553198621040")
        assertEquals("+553198621040", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("3198621", result.prefix7)
        assertEquals("319862", result.prefix6)
        assertEquals("MOBILE", result.lineType)
    }

    @Test
    fun `normalizes 31983211420 11-digit mobile number to E164 and prefix7 3198321`() {
        val result = PhoneNormalizer.normalize("31983211420")
        assertEquals("+5531983211420", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("3198321", result.prefix7)
        assertEquals("319832", result.prefix6)
        assertEquals("MOBILE", result.lineType)
    }

    @Test
    fun `normalizes 0153141011230 with long distance CSP 15`() {
        val result = PhoneNormalizer.normalize("0153141011230")
        assertEquals("+553141011230", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("3141011", result.prefix7)
        assertEquals("314101", result.prefix6)
        assertEquals("FIXED_OR_VOIP", result.lineType)
    }

    @Test
    fun `normalizes 03198621040 removing long-distance zero`() {
        val result = PhoneNormalizer.normalize("03198621040")
        assertEquals("+553198621040", result.normalizedNumber)
        assertEquals("31", result.ddd)
        assertEquals("3198621", result.prefix7)
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

        val r0041 = PhoneNormalizer.normalize("004131939995971")
        assertEquals("+5531939995971", r0041.normalizedNumber)
        assertEquals("31", r0041.ddd)
        assertEquals("3193999", r0041.prefix7)
        assertEquals("TIM", r0041.carrierHint)

        val r10315 = PhoneNormalizer.normalize("03110315")
        assertEquals("+553110315", r10315.normalizedNumber)
        assertEquals("31", r10315.ddd)
        assertEquals("3110315", r10315.prefix7)
        assertEquals("TOLL_FREE_OR_SPECIAL", r10315.lineType)
        assertEquals("VoIP", r10315.carrierHint)
    }

    @Test
    fun `normalizes and infers carriers for real call history numbers from S23 Ultra`() {
        val c1 = PhoneNormalizer.normalize("031987806827")
        assertEquals("+5531987806827", c1.normalizedNumber)
        assertEquals("3198780", c1.prefix7)
        assertEquals("Claro", c1.carrierHint)

        val c2 = PhoneNormalizer.normalize("04111910862316")
        assertEquals("+5511910862316", c2.normalizedNumber)
        assertEquals("11", c2.ddd)
        assertEquals("1191086", c2.prefix7)
        assertEquals("TIM", c2.carrierHint)

        val c3 = PhoneNormalizer.normalize("031976199037")
        assertEquals("+5531976199037", c3.normalizedNumber)
        assertEquals("3197619", c3.prefix7)
        assertEquals("TIM", c3.carrierHint)

        val c4 = PhoneNormalizer.normalize("031986333446")
        assertEquals("+5531986333446", c4.normalizedNumber)
        assertEquals("3198633", c4.prefix7)
        assertEquals("TIM", c4.carrierHint)

        val c5 = PhoneNormalizer.normalize("031939321258")
        assertEquals("+5531939321258", c5.normalizedNumber)
        assertEquals("3193932", c5.prefix7)
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
}
