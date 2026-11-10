package br.com.spamshield.domain

import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.service.reprocessStoredTelemetry
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtSpamshield2026001Test {

    @Test
    fun stage1UtilityShortCodeKeeps10315WithoutPlus55() {
        val phone = PhoneNormalizer.normalize("03110315", "31")
        assertEquals("10315", phone.normalizedNumber)
        assertEquals("31", phone.ddd)
        assertEquals("TOLL_FREE_OR_SPECIAL", phone.lineType)
        assertEquals("Vivo", phone.carrierHint)
        assertTrue(phone.isShortOrUtilityCode)
        assertNull(phone.storedPrefix7)
        val scored = scorePhone(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.NONE)
        assertTrue(scored.triggeredRules.none { it.rule_id == "ANATEL_PABX_VOIP_RANGE" })
    }

    @Test
    fun stage1Csp0041DoesNotBecomeTim() {
        val phone = PhoneNormalizer.normalize("004131939995971", "31")
        assertEquals("+5531939995971", phone.normalizedNumber)
        assertEquals("31", phone.ddd)
        assertEquals("0041", phone.cspExtracted)
        assertEquals("939995971", phone.subscriberNumber)
        assertEquals("MOBILE", phone.lineType)
        assertEquals("Claro", phone.carrierHint)
        assertEquals("31939995", phone.prefix7)
        assertEquals(8, phone.prefix7.length)
    }

    @Test
    fun stage1MalformedMobileLengthAddsSpoofingRule() {
        val phone = PhoneNormalizer.normalize("+553198621040", "31")
        assertEquals("+553198621040", phone.normalizedNumber)
        assertEquals("31", phone.ddd)
        assertEquals("98621040", phone.subscriberNumber)
        assertEquals("MALFORMED_OR_SPOOFED", phone.lineType)
        val scored = scorePhone(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.NONE)
        val rule = scored.triggeredRules.single { it.rule_id == "MALFORMED_NUMBER_SPOOFING" }
        assertEquals(85, rule.weight)
        assertEquals("Mobile length 8 != 9", rule.meta)
        assertScoreEquation(scored)
    }

    @Test
    fun stage1DigitSixToEightLength8IsMalformedAndPenalized() {
        val phone = PhoneNormalizer.normalize("0415476080729", "31")
        assertEquals("76080729", phone.subscriberNumber)
        assertEquals("MALFORMED_OR_SPOOFED", phone.lineType)
        assertFalse(phone.lineType == "MOBILE")
        val scored = scorePhone(phone, stir = "PASSED", quarantine = PrefixQuarantineMatch.NONE)
        val rule = scored.triggeredRules.single { it.rule_id == "MALFORMED_NUMBER_SPOOFING" }
        assertEquals(85, rule.weight)
        assertScoreEquation(scored)
    }

    @Test
    fun stage2EightDigitPrefixAndExactQuarantineWeight() {
        val first = PhoneNormalizer.normalize("004131939995971", "31")
        assertEquals("31939995", first.prefix7)
        val second = PhoneNormalizer.normalize("031987806827", "31")
        assertEquals("31987806", second.prefix7)
        listOf(first to "31939995", second to "31987806").forEach { (phone, prefix) ->
            val scored = scorePhone(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.EXACT8)
            val rule = scored.triggeredRules.single { it.rule_id == "PREFIX_QUARANTINE_HIT" }
            assertEquals(70, rule.weight)
            assertEquals(prefix, rule.meta)
            assertTrue(scored.triggeredRules.none { it.rule_id == "PREFIX7_BROAD_QUARANTINE_HIT" })
        }
    }

    @Test
    fun stage2SevenDigitFallbackUsesADistinctRuleAndWeight() {
        QuarantinePrefixIndex.clear()
        QuarantinePrefixIndex.add("3193999")
        val phone = PhoneNormalizer.normalize("004131939995971", "31")
        assertEquals(PrefixQuarantineMatch.BROAD7, QuarantinePrefixIndex.match(phone.storedPrefix7))
        val scored = scorePhone(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.BROAD7)
        val broad = scored.triggeredRules.single { it.rule_id == "PREFIX7_BROAD_QUARANTINE_HIT" }
        assertEquals(QuarantinePrefixIndex.BROAD_WEIGHT, broad.weight)
        assertEquals(60, broad.weight)
        assertTrue(scored.triggeredRules.none { it.rule_id == "PREFIX_QUARANTINE_HIT" })
        QuarantinePrefixIndex.add("31939995")
        assertEquals(PrefixQuarantineMatch.EXACT8, QuarantinePrefixIndex.match("31939995"))
        val exact = scorePhone(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.EXACT8)
        assertEquals(70, exact.triggeredRules.single { it.rule_id == "PREFIX_QUARANTINE_HIT" }.weight)
        QuarantinePrefixIndex.clear()
    }

    @Test
    fun stage3VerifiedLocalFixedLineStaysAt25() {
        val phone = PhoneNormalizer.normalize("03137686745", "31")
        assertEquals("31376867", phone.prefix7)
        assertEquals("Fixo", phone.carrierHint)
        val scored = HeuristicScorer.score(
            inputFrom(phone, stir = "PASSED", quarantine = PrefixQuarantineMatch.NONE, carrier = "Fixo")
        )
        assertEquals(25, scored.finalScore)
        assertEquals("ALLOWED_LOW_SCORE", scored.outcome)
        assertEquals("VERIFIED_UNKNOWN", scored.category)
        assertEquals(listOf(TriggeredRuleDto("NOT_IN_CONTACTS", 25, null)), scored.triggeredRules)
        assertScoreEquation(scored)
    }

    @Test
    fun stage3VoipTrunkMutexKeepsBlacklistAndScores285() {
        val phone = PhoneNormalizer.normalize("03141011230", "31")
        assertEquals("31410112", phone.prefix7)
        val scored = HeuristicScorer.score(
            inputFrom(phone, stir = "FAILED", quarantine = PrefixQuarantineMatch.EXACT8, carrier = "VoIP")
        )
        assertEquals(285, scored.finalScore)
        assertEquals("BLOCKED", scored.outcome)
        assertEquals("SPOOFING_STIR_FAILED", scored.category)
        assertEquals(
            listOf(
                "NOT_IN_CONTACTS" to 25,
                "STIR_SHAKEN_FAILED" to 85,
                "PREFIX_QUARANTINE_HIT" to 70,
                "BLACKLIST_MG_VOIP_PABX" to 80,
                "LEADING_ZERO_TRUNK_DIALING" to 25
            ),
            scored.triggeredRules.map { it.rule_id to it.weight }
        )
        assertEquals("VERIFICATION_STATUS_FAILED", scored.triggeredRules[1].meta)
        assertEquals("31410112", scored.triggeredRules[2].meta)
        assertEquals(ScreeningPatternCache.BLACKLIST_MG_VOIP_PABX_PATTERN, scored.triggeredRules[3].meta)
        assertEquals("0314", scored.triggeredRules[4].meta)
        assertEquals(1, scored.triggeredRules.count { it.rule_id in HeuristicScorer.VOIP_TRUNK_GROUP })
        assertScoreEquation(scored)
    }

    @Test
    fun stage5CoalescePreservesScreeningDecision() {
        val normalized = PhoneNormalizer.normalize("03130711154", "31")
        assertEquals("+553130711154", normalized.normalizedNumber)
        val store = mutableListOf<CallTelemetryEntity>()
        TelemetryCoalescer.persist(
            store,
            sample(
                id = "177d4560-45b4-4865-8444-0aa7ea5ecbcd",
                timestamp = 1_794_237_214_541L,
                raw = "03130711154",
                normalized = "+553130711154",
                stir = "PASSED",
                duration = 0,
                score = 155,
                outcome = "BLOCKED"
            )
        )
        TelemetryCoalescer.persist(
            store,
            sample(
                id = "1371beb3-3dec-4cc9-99bc-993f46fcb2e0",
                timestamp = 1_794_237_216_651L,
                raw = "03130711154",
                normalized = "+553130711154",
                stir = "NOT_VERIFIED",
                duration = null,
                score = 170,
                outcome = "BLOCKED"
            )
        )
        assertEquals(1, store.size)
        val kept = store.single()
        assertEquals("177d4560-45b4-4865-8444-0aa7ea5ecbcd", kept.id)
        assertEquals(1_794_237_214_541L, kept.timestamp)
        assertEquals("+553130711154", kept.normalizedNumber)
        assertEquals("PASSED", kept.stirShakenStatus)
        assertEquals(0, kept.callDurationSec)
        assertEquals(155, kept.finalScore)
        assertEquals("BLOCKED", kept.outcome)
    }

    @Test
    fun stage5SameTimestampHistoryIsDedupedBeforeScoring() {
        val first = sample(
            id = "cf1631a8-kept",
            timestamp = 1_790_944_698_888L,
            raw = "031999000111",
            normalized = "+5531999000111",
            stir = "NOT_VERIFIED",
            duration = 0,
            score = 170,
            outcome = "BLOCKED"
        )
        val second = first.copy(id = "c8b28626-duplicate", finalScore = 100)
        val deduped = TelemetryCoalescer.dedupeSameTimestamp(listOf(second, first))
        assertEquals(1, deduped.size)
        assertEquals("cf1631a8-kept", deduped.single().id)
    }

    @Test
    fun stage5DurationBandsAndPostCallOutcome() {
        val phone = PhoneNormalizer.normalize("031982996360", "31")
        assertEquals("31982996", phone.prefix7)
        val five = HeuristicScorer.score(
            inputFrom(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.NONE, carrier = "TIM")
                .copy(callDurationSec = 5, historicalUnverified = true)
        )
        assertEquals(95, five.finalScore)
        assertEquals("FLAGGED_POST_CALL_QUARANTINED", five.outcome)
        assertEquals("PING_CALL_ROBOCALL", five.category)
        assertEquals(
            listOf(
                TriggeredRuleDto("NOT_IN_CONTACTS", 25, null),
                TriggeredRuleDto("STIR_SHAKEN_UNVERIFIED", 15, "Histórico"),
                TriggeredRuleDto("PING_CALL_PENALTY", 55, "Duração 5s")
            ),
            five.triggeredRules
        )
        assertScoreEquation(five)

        val eight = HeuristicScorer.score(
            inputFrom(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.NONE, carrier = "TIM")
                .copy(callDurationSec = 8, historicalUnverified = true)
        )
        assertEquals(70, eight.finalScore)
        assertEquals("FLAGGED_POST_CALL_QUARANTINED", eight.outcome)
        assertEquals("SHORT_ROBOCALL_PENALTY", eight.triggeredRules.last().rule_id)
        assertEquals(30, eight.triggeredRules.last().weight)
        assertScoreEquation(eight)

        val twelve = HeuristicScorer.score(
            inputFrom(phone, stir = "NOT_VERIFIED", quarantine = PrefixQuarantineMatch.NONE, carrier = "TIM")
                .copy(callDurationSec = 12, historicalUnverified = true)
        )
        assertEquals(0, twelve.finalScore)
        assertEquals("ALLOWED_LOW_SCORE", twelve.outcome)
        assertEquals("CONVERSATION_ANSWERED_BONUS", twelve.triggeredRules.last().rule_id)
        assertEquals(-40, twelve.triggeredRules.last().weight)
        assertScoreEquation(twelve)
    }

    @Test
    fun stage6ConfirmedSpamRecomputesScoreAndSyncsEveryPendingRow() {
        TelemetrySyncScheduler.resetForTests()
        val initial = sample(
            id = "151fe7f3-5eb8-453d-a27c-d6d1a33207fc",
            timestamp = 1_700_000_000_000L,
            raw = "031996856221",
            normalized = "+5531996856221",
            stir = "NOT_VERIFIED",
            duration = null,
            score = 40,
            outcome = "ALLOWED_LOW_SCORE"
        ).copy(
            thresholdApplied = 70,
            category = "UNKNOWN_UNVERIFIED",
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15,"meta":"VERIFICATION_STATUS_NOT_VERIFIED"}]""",
            userFeedback = "UNRATED",
            syncStatus = "SYNCED"
        )
        val older = sample(
            id = "older-pending-row",
            timestamp = 1_600_000_000_000L,
            raw = "031900000000",
            normalized = "+5531900000000",
            stir = "NOT_VERIFIED",
            duration = null,
            score = 25,
            outcome = "ALLOWED_LOW_SCORE"
        ).copy(syncStatus = "PENDING")
        val updated = UserFeedbackAudit.applyConfirmedSpam(initial)
        assertTrue(TelemetrySyncScheduler.enqueuedCount >= 1)
        assertEquals(140, updated.finalScore)
        assertEquals("USER_BLOCKED_RETROACTIVE", updated.outcome)
        assertEquals("USER_REPORTED_SPAM", updated.category)
        assertEquals("CONFIRMED_SPAM", updated.userFeedback)
        assertEquals("PENDING", updated.syncStatus)
        val rules = UserFeedbackAudit.decode(updated.triggeredRulesJson)
        assertEquals(140, rules.sumOf { it.weight })
        assertEquals("USER_CONFIRMED_SPAM_OVERRIDE", rules.last().rule_id)
        assertEquals(100, rules.last().weight)
        assertEquals("Feedback manual do utilizador", rules.last().meta)
        val selected = SyncSelection.pending(listOf(older, updated))
        assertEquals(setOf(older.id, updated.id), selected.map { it.id }.toSet())
        val synced = SyncSelection.markSynced(listOf(older, updated), selected.map { it.id }.toSet())
        assertTrue(synced.none { it.syncStatus == "PENDING" })
        assertEquals("SYNCED", synced.single { it.id == updated.id }.syncStatus)
    }

    @Test
    fun postCallVerdictDoesNotKeepBlockedWhenDurationIsPositive() = runTest {
        val dao = FakeSpamShieldDao()
        val now = 1_700_000_300_000L
        dao.insertTelemetry(
            sample(
                id = "edge-blocked-answered",
                timestamp = now - 2_000L,
                raw = "03130711154",
                normalized = "+553130711154",
                stir = "PASSED",
                duration = null,
                score = 155,
                outcome = "BLOCKED"
            ).copy(
                thresholdApplied = 70,
                lineType = "FIXED_OR_VOIP",
                carrierGuess = "VoIP",
                prefix7 = "31307111",
                triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_FAILED","weight":85,"meta":"VERIFICATION_STATUS_FAILED"},{"rule_id":"PREFIX_QUARANTINE_HIT","weight":45,"meta":"31307111"}]"""
            )
        )
        val engine = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())
        val changed = engine.evaluatePostCallLogVerdict(
            normalizedNumber = "+553130711154",
            callType = android.provider.CallLog.Calls.INCOMING_TYPE,
            callDurationSec = 5,
            nowMillis = now
        )
        assertTrue(changed)
        val updated = dao.getTelemetryById("edge-blocked-answered")
        assertEquals(5, updated?.callDurationSec)
        assertEquals("FLAGGED_POST_CALL_QUARANTINED", updated?.outcome)
        val rules = UserFeedbackAudit.decode(updated!!.triggeredRulesJson)
        assertEquals(HeuristicScorer.equation(rules), updated.finalScore)
        assertTrue(updated.finalScore >= 70)
    }

    @Test
    fun rejectedEmergencyRepeatWithPositiveDurationStaysUnderThreshold() = runTest {
        val dao = FakeSpamShieldDao()
        val now = 1_700_000_400_000L
        dao.insertTelemetry(
            sample(
                id = "emergency-then-rejected",
                timestamp = now - 1_000L,
                raw = "031999001122",
                normalized = "+5531999001122",
                stir = "PASSED",
                duration = null,
                score = -500,
                outcome = "ALLOWED_EMERGENCY_REPEAT"
            ).copy(
                thresholdApplied = 70,
                lineType = "MOBILE",
                carrierGuess = "TIM",
                prefix7 = "31999001",
                triggeredRulesJson = """[{"rule_id":"EMERGENCY_REPEATED_CALL","weight":-500}]"""
            )
        )
        val engine = SpamScoringEngine(dao, SettingsDataStore(), FakeContactLookup())
        engine.evaluatePostCallLogVerdict(
            normalizedNumber = "+5531999001122",
            callType = android.provider.CallLog.Calls.REJECTED_TYPE,
            callDurationSec = 5,
            nowMillis = now
        )
        val updated = dao.getTelemetryById("emergency-then-rejected")
        val rules = UserFeedbackAudit.decode(updated!!.triggeredRulesJson)
        assertEquals(5, updated.callDurationSec)
        assertEquals("ALLOWED_LOW_SCORE", updated.outcome)
        assertEquals(HeuristicScorer.equation(rules), updated.finalScore)
        assertTrue(updated.finalScore < 70)
        assertTrue(rules.any { it.rule_id == "PING_CALL_PENALTY" && it.weight == 55 })
        assertTrue(dao.getAllQuarantines().none { it.prefix7 == "31999001" && it.reason == "DIALER_REJECTED" })
    }

    @Test
    fun reprocessUsesScorerForVoipMutexAndVerifiedFixedConversation() = runTest {
        val dao = FakeSpamShieldDao()
        dao.deleteQuarantineByPrefix("3141011")
        val now = System.currentTimeMillis()
        dao.upsertQuarantine(
            PrefixQuarantineEntity(
                prefix7 = "31410112",
                ddd = "31",
                carrierGuess = "VoIP",
                hitCount = 2,
                penaltyWeight = 70,
                reason = "ROBOCALL_PREFIX_CLUSTER",
                firstSeenAt = now,
                lastSeenAt = now,
                expiresAt = now + 86_400_000L
            )
        )
        dao.insertTelemetry(
            sample(
                id = "voip-03141011230",
                timestamp = now - 10_000L,
                raw = "03141011230",
                normalized = "+553141011230",
                stir = "FAILED",
                duration = 0,
                score = 400,
                outcome = "BLOCKED"
            ).copy(
                deviceId = "s23-ultra-history",
                prefix7 = "3141011",
                lineType = "FIXED_OR_VOIP",
                carrierGuess = "VoIP",
                triggeredRulesJson = """[{"rule_id":"REGEX_MG_VOIP_TRUNKS","weight":80},{"rule_id":"ANATEL_PABX_VOIP_RANGE","weight":35}]"""
            )
        )
        dao.insertTelemetry(
            sample(
                id = "fixo-passed-conversation",
                timestamp = now - 20_000L,
                raw = "03137686745",
                normalized = "+553137686745",
                stir = "PASSED",
                duration = 12,
                score = -15,
                outcome = "ALLOWED_LOW_SCORE"
            ).copy(
                deviceId = "s23-ultra-history",
                prefix7 = "31376867",
                lineType = "FIXED_OR_VOIP",
                carrierGuess = "Fixo",
                category = "UNKNOWN_UNVERIFIED",
                triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"CONVERSATION_ANSWERED_BONUS","weight":-40}]"""
            )
        )
        val updated = reprocessStoredTelemetry(dao, SettingsDataStore())
        assertTrue(updated >= 2)
        val voip = dao.getTelemetryById("voip-03141011230")
        val voipRules = UserFeedbackAudit.decode(voip!!.triggeredRulesJson)
        assertEquals(285, voip.finalScore)
        assertEquals("BLOCKED", voip.outcome)
        assertEquals("SPOOFING_STIR_FAILED", voip.category)
        assertEquals(HeuristicScorer.equation(voipRules), voip.finalScore)
        assertTrue(voipRules.any { it.rule_id == "BLACKLIST_MG_VOIP_PABX" && it.weight == 80 })
        assertEquals(1, voipRules.count { it.rule_id in HeuristicScorer.VOIP_TRUNK_GROUP })
        assertTrue(voipRules.none { it.rule_id == "REGEX_MG_VOIP_TRUNKS" })
        assertTrue(voipRules.none { it.rule_id == "ANATEL_PABX_VOIP_RANGE" })

        val fixed = dao.getTelemetryById("fixo-passed-conversation")
        val fixedRules = UserFeedbackAudit.decode(fixed!!.triggeredRulesJson)
        assertEquals(0, fixed.finalScore)
        assertEquals("ALLOWED_LOW_SCORE", fixed.outcome)
        assertEquals("VERIFIED_UNKNOWN", fixed.category)
        assertEquals(25, fixedRules.single { it.rule_id == "NOT_IN_CONTACTS" }.weight)
        assertEquals(-40, fixedRules.single { it.rule_id == "CONVERSATION_ANSWERED_BONUS" }.weight)
        assertEquals(HeuristicScorer.equation(fixedRules), fixed.finalScore)
    }

    @Test
    fun stage4ScreeningUsesMemoryQuarantineAndProcessLifetimePatterns() = runTest {
        val pattern = ScreeningPatternCache.blacklistMgVoipPabx()
        assertTrue(pattern === ScreeningPatternCache.pattern(ScreeningPatternCache.BLACKLIST_MG_VOIP_PABX_PATTERN))
        val screening = functionBody(
            "br/com/spamshield/domain/SpamScoringEngine.kt",
            "suspend fun evaluateIncomingCall",
            "fun extractQuarantinePrefix"
        )
        assertFalse(screening.contains("Regex("))
        assertFalse(screening.contains("Pattern.compile"))
        assertFalse(screening.contains("getActiveQuarantine"))
        assertFalse(screening.contains("SELECT"))
        val service = readSource("br/com/spamshield/service/SmartCallScreeningService.kt")
        assertFalse(service.contains("Regex("))
        assertFalse(service.contains("Pattern.compile"))
        assertFalse(service.contains("SELECT"))

        QuarantinePrefixIndex.clear()
        QuarantinePrefixIndex.add("31410112")
        val dao = object : FakeSpamShieldDao() {
            override suspend fun getActiveQuarantineForPrefix(prefix8: String, prefix7: String, now: Long): PrefixQuarantineEntity? {
                throw AssertionError("synchronous quarantine query")
            }
            override suspend fun getActiveQuarantineForPrefix(prefix7: String, now: Long): PrefixQuarantineEntity? {
                throw AssertionError("synchronous quarantine query")
            }
            override suspend fun countActiveQuarantinesByPrefix6(prefix6: String, now: Long): Int {
                throw AssertionError("synchronous quarantine query")
            }
            override suspend fun countActiveQuarantinesByPrefix5(prefix5: String, now: Long): Int {
                throw AssertionError("synchronous quarantine query")
            }
        }
        val engine = SpamScoringEngine(dao, br.com.spamshield.data.local.SettingsDataStore(), FakeContactLookup())
        val result = engine.evaluateIncomingCall("03141011230", "FAILED", 1_700_000_010_000L)
        val rules = UserFeedbackAudit.decode(result.telemetry.triggeredRulesJson)
        val hit = rules.single { it.rule_id == "PREFIX_QUARANTINE_HIT" }
        assertEquals(70, hit.weight)
        assertEquals("31410112", hit.meta)
        assertEquals(HeuristicScorer.equation(rules), result.telemetry.finalScore)
        QuarantinePrefixIndex.clear()
    }

    @Test
    fun stage7SanitizerHoldsTheFiveInvariants() {
        val rows = etapa7Fixture()
        val sanitized = TelemetrySanitizer.sanitize(rows)
        assertTrue(sanitized.none { it.id.startsWith("4b98f6ee") })
        assertTrue(sanitized.none { it.id.startsWith("1371beb3") })
        assertTrue(sanitized.none { it.id.startsWith("c8b28626") })
        assertTrue(sanitized.none { it.id.startsWith("203688fe") })
        val rewritten = sanitized.single { it.id == "len7-mobile" }
        assertEquals("31987806", rewritten.prefix7)
        val confirmed = sanitized.single { it.id == "151fe7f3-5eb8-453d-a27c-d6d1a33207fc" }
        assertTrue(confirmed.triggeredRulesJson.contains("USER_CONFIRMED_SPAM_OVERRIDE"))
        assertEquals(140, confirmed.finalScore)
        listOf("96854886", "0e43a524", "e68988dc").forEach { prefix ->
            assertEquals("PENDING", sanitized.single { it.id.startsWith(prefix) }.syncStatus)
        }
        sanitized.forEach { row ->
            val sum = UserFeedbackAudit.decode(row.triggeredRulesJson).sumOf { it.weight }.coerceAtLeast(0)
            assertEquals(sum, row.finalScore)
            if (row.lineType == "MOBILE" || row.lineType == "FIXED_OR_VOIP") {
                assertEquals(8, row.prefix7.length)
            }
        }
        val fixed = sanitized.filter {
            it.stirShakenStatus == "PASSED" &&
                it.carrierGuess == "Fixo" &&
                !it.triggeredRulesJson.contains("PREFIX_QUARANTINE_HIT")
        }
        assertTrue(fixed.isNotEmpty())
        assertTrue(fixed.all { it.finalScore < 70 })
        sanitized.forEach { left ->
            val clash = sanitized.any { right ->
                right.id != left.id &&
                    right.normalizedNumber == left.normalizedNumber &&
                    kotlin.math.abs(right.timestamp - left.timestamp) <= 15_000L
            }
            assertFalse(clash)
        }
        val synced = SyncSelection.complete(sanitized)
        assertTrue(synced.none { it.syncStatus == "PENDING" })
    }

    private fun etapa7Fixture(): List<CallTelemetryEntity> {
        val fixedBad = sample(
            id = "86155641-677c-49fb-8f80-5bb5213f6edc",
            timestamp = 1_794_237_222_816L,
            raw = "03137686745",
            normalized = "+553137686745",
            stir = "PASSED",
            duration = 0,
            score = 85,
            outcome = "BLOCKED"
        ).copy(
            prefix7 = "3137686",
            carrierGuess = "Fixo",
            lineType = "FIXED_OR_VOIP",
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"ANATEL_PABX_VOIP_RANGE","weight":35},{"rule_id":"LEADING_ZERO_TRUNK_DIALING","weight":25}]""",
            syncStatus = "SYNCED"
        )
        return listOf(
            fixedBad,
            sample("keep-same-ms", 1_791_555_733_869L, "03110315", "10315", "NOT_VERIFIED", 0, 25, "ALLOWED_LOW_SCORE"),
            sample("4b98f6ee-second", 1_791_555_733_869L, "03110315", "10315", "NOT_VERIFIED", 0, 40, "ALLOWED_LOW_SCORE"),
            sample("177d4560-45b4-4865-8444-0aa7ea5ecbcd", 1_794_237_214_541L, "03130711154", "+553130711154", "PASSED", 0, 155, "BLOCKED")
                .copy(prefix7 = "31307111", lineType = "FIXED_OR_VOIP", carrierGuess = "VoIP"),
            sample("1371beb3-3dec-4cc9-99bc-993f46fcb2e0", 1_794_237_216_651L, "03130711154", "+553130711154", "NOT_VERIFIED", null, 170, "BLOCKED"),
            sample("len7-mobile", 1_700_000_100_000L, "031987806827", "+5531987806827", "NOT_VERIFIED", 0, 25, "ALLOWED_LOW_SCORE")
                .copy(prefix7 = "3198780", lineType = "MOBILE", carrierGuess = "Claro", triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]"""),
            sample("151fe7f3-5eb8-453d-a27c-d6d1a33207fc", 1_700_000_200_000L, "031996856221", "+5531996856221", "NOT_VERIFIED", null, 100, "BLOCKED")
                .copy(
                    prefix7 = "31996856",
                    lineType = "MOBILE",
                    userFeedback = "CONFIRMED_SPAM",
                    triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15,"meta":"VERIFICATION_STATUS_NOT_VERIFIED"}]"""
                ),
            forcedSpam("96854886-39ed-4f46-adb5-d09b06895713", "+5531991000001", 1_680_000_000_000L),
            forcedSpam("0e43a524-3cc3-4b4d-9736-ac557f5ec63b", "+5531991000002", 1_681_000_000_000L),
            forcedSpam("e68988dc-14b7-4f13-ad9c-ede78e65ea32", "+5531991000003", 1_682_000_000_000L),
            sample("c8b28626-dup", 1_790_944_698_888L, "031988360000", "+5531988360000", "NOT_VERIFIED", 0, 100, "BLOCKED")
                .copy(prefix7 = "31988360", lineType = "MOBILE"),
            sample("cf1631a8-keep", 1_790_944_698_888L, "031988360000", "+5531988360000", "NOT_VERIFIED", 0, 25, "ALLOWED_LOW_SCORE")
                .copy(prefix7 = "31988360", lineType = "MOBILE", triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]"""),
            sample("203688fe-race", 1_794_237_224_687L, "03137682020", "+553137682020", "NOT_VERIFIED", null, 170, "BLOCKED"),
            sample("de65c16e-keep", 1_794_237_222_816L, "03137682020", "+553137682020", "PASSED", 0, 25, "ALLOWED_LOW_SCORE")
                .copy(
                    prefix7 = "31376820",
                    lineType = "FIXED_OR_VOIP",
                    carrierGuess = "Fixo",
                    triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]"""
                ),
            sample("burst-kept", 1_792_718_165_716L, "031988000111", "+5531988000111", "NOT_VERIFIED", 3, 80, "FLAGGED_POST_CALL_QUARANTINED")
                .copy(prefix7 = "31988000", lineType = "MOBILE", triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"PING_CALL_PENALTY","weight":55}]"""),
            sample("burst-null", 1_792_718_167_000L, "031988000111", "+5531988000111", "NOT_VERIFIED", null, 25, "ALLOWED_LOW_SCORE")
                .copy(prefix7 = "31988000", lineType = "MOBILE", triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]""")
        )
    }

    private fun forcedSpam(id: String, number: String, timestamp: Long) = sample(
        id = id,
        timestamp = timestamp,
        raw = number,
        normalized = number,
        stir = "NOT_VERIFIED",
        duration = null,
        score = 100,
        outcome = "BLOCKED"
    ).copy(
        prefix7 = "31991000",
        lineType = "MOBILE",
        userFeedback = "CONFIRMED_SPAM",
        syncStatus = "PENDING",
        triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"STIR_SHAKEN_UNVERIFIED","weight":15},{"rule_id":"ANATEL_PABX_VOIP_RANGE","weight":35},{"rule_id":"LEADING_ZERO_TRUNK_DIALING","weight":10}]"""
    )

    private fun scorePhone(
        phone: NormalizedPhone,
        stir: String,
        quarantine: PrefixQuarantineMatch
    ) = HeuristicScorer.score(inputFrom(phone, stir, quarantine, phone.carrierHint))

    private fun inputFrom(
        phone: NormalizedPhone,
        stir: String,
        quarantine: PrefixQuarantineMatch,
        carrier: String?
    ) = ScoreInput(
        rawNumber = phone.rawNumber,
        normalizedNumber = phone.normalizedNumber,
        ddd = phone.ddd,
        prefix7 = phone.storedPrefix7 ?: phone.prefix7.takeIf { it.length == 8 },
        subscriberNumber = phone.subscriberNumber,
        carrierGuess = carrier,
        lineType = phone.lineType,
        stirShakenStatus = stir,
        quarantineMatch = quarantine,
        cspExtracted = phone.cspExtracted,
        isShortOrUtilityCode = phone.isShortOrUtilityCode,
        thresholdApplied = 70
    )

    private fun assertScoreEquation(result: br.com.spamshield.domain.ScoreResult) {
        val sum = result.triggeredRules.sumOf { it.weight }.coerceAtLeast(0)
        assertEquals(sum, result.finalScore)
    }

    private fun sample(
        id: String,
        timestamp: Long,
        raw: String,
        normalized: String,
        stir: String,
        duration: Int?,
        score: Int,
        outcome: String
    ) = CallTelemetryEntity(
        id = id,
        timestamp = timestamp,
        rawNumber = raw,
        normalizedNumber = normalized,
        ddd = "31",
        prefix7 = "31000000",
        carrierGuess = "Outros",
        lineType = "MOBILE",
        stirShakenStatus = stir,
        finalScore = score,
        thresholdApplied = 70,
        outcome = outcome,
        category = "UNKNOWN_UNVERIFIED",
        processingLatencyMs = 1.0,
        shadowModeActive = false,
        callDurationSec = duration,
        triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]""",
        syncStatus = "SYNCED"
    )

    private fun functionBody(relative: String, startMarker: String, endMarker: String): String {
        val source = readSource(relative)
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + 1)
        assertTrue(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun readSource(relative: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("SpamShieldApp/app/src/main/java"),
            File("C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java")
        )
        val file = roots.map { File(it, relative) }.firstOrNull { it.exists() }
            ?: throw AssertionError("missing source $relative")
        return file.readText()
    }
}
