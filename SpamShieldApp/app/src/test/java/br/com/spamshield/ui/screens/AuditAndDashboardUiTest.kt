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
}
