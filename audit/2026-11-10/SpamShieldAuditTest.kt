package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.service.reprocessStoredTelemetry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.regex.PatternSyntaxException

/** Characterization tests: passing assertions reproduce defects; they do not endorse the behavior. */
class SpamShieldAuditTest {
    private val now = 1_794_400_000_000L
    private val raw = "031995201234"
    private val prefix = "31995201"

    @Before fun reset() { QuarantinePrefixIndex.clear() }
    @After fun cleanup() { QuarantinePrefixIndex.clear() }

    private fun dao() = FakeSpamShieldDao().apply {
        rules.clear()
        quarantines.clear()
        anatelPrefixes.clear()
    }

    private fun quarantine(at: Long = now) = PrefixQuarantineEntity(
        prefix7 = prefix, ddd = "31", carrierGuess = "Vivo",
        hitCount = 1, penaltyWeight = 85, reason = "CONFIRMED_SPAM",
        firstSeenAt = at, lastSeenAt = at, expiresAt = at + 86_400_000L
    )

    private fun engine(dao: FakeSpamShieldDao) = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())

    @Test fun databaseQuarantineIsIgnoredUntilCacheIsExplicitlyRefreshed() = runTest {
        val dao = dao()
        dao.replaceAllQuarantines(listOf(quarantine())) // Same DAO operation used by sync.
        val unprotected = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now)
        assertFalse(unprotected.shouldDisallowAndReject)
        assertEquals(45, unprotected.telemetry.finalScore)
        dao.telemetries.clear()
        QuarantinePrefixIndex.replaceAll(dao.getAllQuarantines().map { it.prefix7 })
        val protected = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now)
        assertTrue(protected.shouldDisallowAndReject)
        assertEquals(140, protected.telemetry.finalScore)
    }

    @Test fun deletedDatabaseQuarantineRemainsEffectiveInCache() = runTest {
        val dao = dao()
        dao.upsertQuarantine(quarantine())
        QuarantinePrefixIndex.add(prefix)
        dao.deleteQuarantineByPrefix(prefix)
        assertTrue(dao.quarantines.isEmpty())
        assertTrue(engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now).shouldDisallowAndReject)
    }

    @Test fun startupCacheLoadsExpiredQuarantineWithoutExpiryInformation() = runTest {
        val dao = dao()
        dao.upsertQuarantine(quarantine(now - 2 * 86_400_000L))
        assertNull(dao.getActiveQuarantineForPrefix(prefix, now))
        QuarantinePrefixIndex.replaceAll(dao.getAllQuarantines().map { it.prefix7 })
        assertTrue(engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now).shouldDisallowAndReject)
    }

    @Test fun staleCacheLetsEmergencyRepeatOverrideConfirmedDatabaseSpam() = runTest {
        val dao = dao()
        val engine = engine(dao)
        assertFalse(engine.evaluateIncomingCall(raw, "NOT_VERIFIED", now).shouldDisallowAndReject)
        dao.upsertQuarantine(quarantine()) // Also the operation used by manual feedback.
        val repeat = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", now + 60_000L)
        assertEquals("ALLOWED_EMERGENCY_REPEAT", repeat.telemetry.outcome)
        assertFalse(repeat.shouldDisallowAndReject)
    }

    @Test fun reprocessingUsesFutureNumbersMoreThan24HoursApartAndRewritesArrival() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val first = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", now - 3 * 86_400_000L)
        val second = engine.evaluateIncomingCall("031995201235", "NOT_VERIFIED", now)
        assertFalse(first.shouldDisallowAndReject)
        assertFalse(second.shouldDisallowAndReject)
        reprocessStoredTelemetry(dao, SettingsDataStore())
        val rewritten = dao.getTelemetryById(first.telemetry.id)!!
        assertEquals("BLOCKED", rewritten.outcome)
        assertEquals(105, rewritten.finalScore)
        assertTrue(UserFeedbackAudit.decode(rewritten.triggeredRulesJson).any { it.rule_id == "PREFIX_NEIGHBORHOOD_BURST" })
        assertTrue(dao.quarantines.any { it.prefix7 == prefix })
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match(prefix))
    }

    @Test fun manualDialerRejectionRewritesAllowedArrivalAsBlocked() = runTest {
        val dao = dao()
        val engine = engine(dao)
        val arrival = engine.evaluateIncomingCall(raw, "NOT_VERIFIED", now)
        assertFalse(arrival.shouldDisallowAndReject)
        engine.evaluatePostCallLogVerdict(
            arrival.telemetry.normalizedNumber, callType = 5, callDurationSec = 0,
            callTimestampMs = now, nowMillis = now + 10_000L
        )
        val updated = dao.getTelemetryById(arrival.telemetry.id)!!
        assertEquals("BLOCKED", updated.outcome)
        assertEquals(90, updated.finalScore)
        assertTrue(UserFeedbackAudit.decode(updated.triggeredRulesJson).any { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" })
    }

    @Test fun malformedConfiguredRegexAbortsEvenAnExactQuarantineHit() = runTest {
        val dao = dao()
        QuarantinePrefixIndex.add(prefix)
        dao.rules.add(ActiveRuleEntity("INVALID", "Invalid regex", "REGEX_MATCH", "[", 90, 1, true, now))
        var threw = false
        try { engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now) }
        catch (_: PatternSyntaxException) { threw = true }
        assertTrue("SmartCallScreeningService catches this exception and responds with an allow response", threw)
        assertTrue(dao.telemetries.isEmpty())
    }

    @Test fun nationalDigitsOnlyCustomRegexNoLongerMatches() = runTest {
        val dao = dao()
        val pattern = "^319952\\d{5}$"
        assertTrue(Regex(pattern).matches(PhoneNormalizer.normalize(raw).nationalDigits))
        dao.rules.add(ActiveRuleEntity("CUSTOM_NATIONAL", "National regex", "REGEX_MATCH", pattern, 90, 1, true, now))
        val result = engine(dao).evaluateIncomingCall(raw, "NOT_VERIFIED", now)
        assertFalse(result.shouldDisallowAndReject)
        assertEquals(45, result.telemetry.finalScore)
        assertTrue(UserFeedbackAudit.decode(result.telemetry.triggeredRulesJson).none { it.rule_id == "CUSTOM_NATIONAL" })
    }
}
