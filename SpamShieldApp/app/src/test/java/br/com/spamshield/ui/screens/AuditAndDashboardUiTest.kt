package br.com.spamshield.ui.screens

import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.domain.Campaign
import br.com.spamshield.domain.ExpectedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditAndDashboardUiTest {

    private val baseTime = 1_795_255_000_000L

    private fun call(id: String, offsetSec: Long, prefix: String = "31920115", suffix: String = "001"): CallTelemetryEntity =
        CallTelemetryEntity(
            id = id,
            timestamp = baseTime + offsetSec * 1_000L,
            rawNumber = "+55$prefix$suffix",
            normalizedNumber = "+55$prefix$suffix",
            ddd = prefix.take(2),
            prefix7 = prefix,
            carrierGuess = "TIM",
            lineType = "MOBILE",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 80,
            thresholdApplied = 70,
            outcome = "BLOCKED",
            category = "ROBOCALL_PREFIX_CLUSTER",
            processingLatencyMs = 1.2,
            shadowModeActive = false,
            triggeredRulesJson = "[]"
        )

    @Test
    fun protectionOffStateReflectsActiveExpectedWindowAndShadowMode() {
        val window = ExpectedWindow.start(baseTime, 30, keepExact = true)
        val activeAtMidpoint = window.takeIf { it.activeAt(baseTime + 60_000L) }
        val activeAfterExpiry = window.takeIf { it.activeAt(window.expiresAt) }

        assertFalse(isProtectionOff(activeWindow = null, shadowModeEnabled = false))
        assertTrue(isProtectionOff(activeWindow = activeAtMidpoint, shadowModeEnabled = false))
        assertFalse(isProtectionOff(activeWindow = activeAfterExpiry, shadowModeEnabled = false))
        assertTrue(isProtectionOff(activeWindow = null, shadowModeEnabled = true))
    }

    @Test
    fun auditFeedOrdersCampaignsAndCallsByLatestCallDateAndConsolidatesMembers() {
        // Older campaign: calls at +10s, +40s, +90s (latest = +90s)
        val old1 = call("old-1", 10, prefix = "31920045", suffix = "101")
        val old2 = call("old-2", 40, prefix = "31920045", suffix = "102")
        val old3 = call("old-3", 90, prefix = "31920045", suffix = "103")
        val olderCampaign = Campaign(
            id = "camp-old",
            prefix = "31920045",
            firstCallAt = old1.timestamp,
            lastCallAt = old3.timestamp,
            criterionSatisfiedAt = old3.timestamp,
            detectedAt = old3.timestamp,
            memberIds = listOf(old1.id, old2.id, old3.id),
            distinctNumbers = 3
        )

        // Standalone call between the two campaigns (+200s)
        val middleStandalone = call("mid-standalone", 200, prefix = "31988881", suffix = "999")

        // Newer campaign: calls at +300s, +330s, +360s (latest = +360s)
        val new1 = call("new-1", 300, prefix = "31920115", suffix = "201")
        val new2 = call("new-2", 330, prefix = "31920115", suffix = "202")
        val new3 = call("new-3", 360, prefix = "31920115", suffix = "203")
        val newerCampaign = Campaign(
            id = "camp-new",
            prefix = "31920115",
            firstCallAt = new1.timestamp,
            lastCallAt = new3.timestamp,
            criterionSatisfiedAt = new3.timestamp,
            detectedAt = new3.timestamp,
            memberIds = listOf(new1.id, new2.id, new3.id),
            distinctNumbers = 3
        )

        // Most recent standalone call (+500s)
        val latestStandalone = call("latest-standalone", 500, prefix = "31977772", suffix = "555")

        val telemetryList = listOf(
            latestStandalone, new3, new2, new1, middleStandalone, old3, old2, old1
        )
        val feed = buildAuditFeedItems(
            telemetryList = telemetryList,
            campaigns = listOf(olderCampaign, newerCampaign)
        )

        assertEquals(4, feed.size)
        assertEquals(
            listOf(
                "call-latest-standalone",
                "campaign-camp-new",
                "call-mid-standalone",
                "campaign-camp-old"
            ),
            feed.map { it.itemKey }
        )

        val renderedNewCampaign = feed[1] as AuditFeedItem.CampaignItem
        assertEquals(listOf("new-3", "new-2", "new-1"), renderedNewCampaign.members.map { it.id })
        assertEquals(new3.timestamp, renderedNewCampaign.sortTimestamp)

        val renderedOldCampaign = feed[3] as AuditFeedItem.CampaignItem
        assertEquals(listOf("old-3", "old-2", "old-1"), renderedOldCampaign.members.map { it.id })
        assertEquals(old3.timestamp, renderedOldCampaign.sortTimestamp)

        assertEquals(feed.size, feed.map { it.itemKey }.distinct().size)
    }

    @Test
    fun auditFeedSkipsGhostCampaignWithNoMatchingTelemetryRows() {
        val standalone = call("solo-1", 120)
        val ghostCampaign = Campaign(
            id = "ghost",
            prefix = "31999999",
            firstCallAt = baseTime + 500_000L,
            lastCallAt = baseTime + 600_000L,
            criterionSatisfiedAt = baseTime + 600_000L,
            detectedAt = baseTime + 600_000L,
            memberIds = listOf("missing-a", "missing-b", "missing-c"),
            distinctNumbers = 3
        )

        val feed = buildAuditFeedItems(listOf(standalone), listOf(ghostCampaign))
        assertEquals(listOf("call-solo-1"), feed.map { it.itemKey })
    }

    @Test
    fun outcomeBadgeMapsSpamShieldConfirmedAndroidBlockToRedBadgeAndDistinguishesStates() {
        val confirmed = resolveOutcomeBadgeSpec("Bloqueio do SpamShield registrado pelo Android")
        assertEquals("BLOQUEIO REGISTRADO", confirmed.label)
        assertEquals(0xFFDC2626, confirmed.colorArgb)

        assertEquals("REJEITADA", resolveOutcomeBadgeSpec("Rejeitada no discador", wasLearnedAfterRing = true).label)
        assertEquals("SOLICITADO", resolveOutcomeBadgeSpec("Bloqueio solicitado (resultado desconhecido)").label)
        assertEquals("SILENCIADA", resolveOutcomeBadgeSpec("Silenciamento solicitado (resultado desconhecido)").label)
        assertEquals("LEGADO", resolveOutcomeBadgeSpec("Legado: decisão original não registrada").label)
        assertEquals("ATENDIDA", resolveOutcomeBadgeSpec("Atendida").label)
        assertEquals("EFETUADA", resolveOutcomeBadgeSpec("Chamada efetuada").label)
        assertEquals("AUDITADA", resolveOutcomeBadgeSpec("Passagem autorizada pelo app").label)
    }

    @Test
    fun carrierClassifierAndFeatureFormattingHelpersCoverAllOperationalCases() {
        assertEquals("Fixo", classifyCarrierLabel(call("f1", 0, prefix = "31328732").copy(carrierGuess = "Fixo", lineType = "FIXED_OR_VOIP")))
        assertEquals("VoIP", classifyCarrierLabel(call("v1", 0, prefix = "31410112").copy(carrierGuess = "Claro Fixo", lineType = "FIXED_OR_VOIP")))
        assertEquals("Fixo", classifyCarrierLabel(call("f2", 0, prefix = "31376812").copy(carrierGuess = null, lineType = "FIXED_OR_VOIP")))
        assertEquals("VoIP", classifyCarrierLabel(call("u1", 0, prefix = "").copy(carrierGuess = null, lineType = "TOLL_FREE_OR_SPECIAL")))
        assertEquals("Claro", classifyCarrierLabel(call("c1", 0).copy(carrierGuess = "Claro S.A.")))
        assertEquals("TIM", classifyCarrierLabel(call("t1", 0).copy(carrierGuess = "TIM")))
        assertEquals("Vivo", classifyCarrierLabel(call("vv1", 0).copy(carrierGuess = "Vivo")))
        assertEquals("Outros", classifyCarrierLabel(call("o1", 0).copy(carrierGuess = "MVNO Regional")))

        assertEquals("5531••••", maskedNumber("+55 31 98765-4321"))
        assertEquals("Número oculto", maskedNumber("10315"))
        assertEquals("15 min", durationLabel(15))
        assertEquals("1 hora", durationLabel(60))
        assertEquals("2 horas", durationLabel(120))
        assertEquals("Contexto original indisponível", labReason("ARRIVAL_CONTEXT_UNAVAILABLE"))
        assertEquals("Permissão protegida", labReason("PROTECTED_ALLOW"))
        assertEquals("CUSTOM_RULE", labReason("CUSTOM_RULE"))

        val latency = br.com.spamshield.domain.ScreeningLatency(
            evaluationWaitMs = 0.4,
            localContextMs = 1.2,
            rulesMs = 0.8,
            submissionMs = 0.5
        )
        assertTrue(latency.elapsedLabel()!!.contains("contexto"))
        assertEquals(null, br.com.spamshield.domain.ScreeningLatency().elapsedLabel())
    }
}
