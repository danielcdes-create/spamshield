package br.com.spamshield.ui

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.domain.DecisionDetails
import br.com.spamshield.domain.FakeContactLookup
import br.com.spamshield.domain.FakeSpamShieldDao
import br.com.spamshield.domain.FeatureJournal
import br.com.spamshield.domain.FeaturePayload
import br.com.spamshield.domain.FeatureRepository
import br.com.spamshield.domain.QuarantinePrefixIndex
import br.com.spamshield.domain.ScreeningPolicyDecision
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.domain.TelemetrySyncScheduler
import br.com.spamshield.service.CallHistoryAnalyzer
import br.com.spamshield.sync.SyncRepository
import br.com.spamshield.sync.TestHttpResponse
import br.com.spamshield.sync.TestLoopbackHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelAndSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private lateinit var server: TestLoopbackHttpServer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefsFile = File(tempFolder.newFolder("datastore"), "vm.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile }
        settings = SettingsDataStore(dataStore)
        server = TestLoopbackHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
        storeScope.cancel()
        Dispatchers.resetMain()
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
    }

    private fun testContext(): ContextWrapper {
        val cache = tempFolder.newFolder("cache")
        val files = tempFolder.newFolder("files")
        return object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
            override fun getFilesDir(): File = files
            override fun getExternalFilesDir(type: String?): File = files
            override fun getPackageName(): String = "br.com.spamshield"
            override fun getSystemService(name: String): Any? = null
        }
    }

    @Test
    fun `SettingsDataStore clamps threshold, sanitizes DDD and revokes remote consent when server URL changes`() = runBlocking {
        settings.acceptLocalDataDisclosure()
        settings.setShadowMode(true)
        settings.setHideBlockedFromSystemCallLog(true)
        settings.setBlockThreshold(5)
        assertEquals(20, settings.getSnapshot().blockThresholdScore)
        settings.setBlockThreshold(999)
        assertEquals(150, settings.getSnapshot().blockThresholdScore)

        settings.setUserDdd("(11) SP")
        assertEquals("11", settings.getSnapshot().userDdd)
        settings.setApiBearerToken("  token-abc  ")
        assertEquals("token-abc", settings.getSnapshot().apiBearerToken)

        settings.setTailscaleServerUrl("http://100.101.23.111:8080/")
        settings.setRemoteSyncEnabled(true)
        assertTrue(settings.getSnapshot().remoteSyncEnabled)

        settings.setTailscaleServerUrl("http://100.101.23.111:8080")
        assertTrue(settings.getSnapshot().remoteSyncEnabled)

        settings.setTailscaleServerUrl("http://100.83.253.15:8080")
        val snap = settings.getSnapshot()
        assertFalse(snap.remoteSyncEnabled)
        assertTrue(snap.localDataDisclosureAccepted)
        assertTrue(snap.shadowModeEnabled)
        assertTrue(snap.hideBlockedFromSystemCallLog)
    }

    @Test
    fun `MainViewModel validates custom regex, normalizes manual quarantines, simulates calls and exports RFC-4180 CSV plus JSON`() = runBlocking {
        server.route("/api/v1/health") {
            TestHttpResponse.json(200, """{"status":"ok","mongodb_connected":true}""")
        }
        server.route("/api/v1/apk/info") {
            val sha = "a".repeat(64)
            val nextBuild = BuildConfig.VERSION_CODE + 1
            TestHttpResponse.json(200, """{"version_code":$nextBuild,"version_name":"1.4.2","size_bytes":5242880,"sha256_checksum":"$sha"}""")
        }
        settings.setTailscaleServerUrl(server.baseUrl)

        val ctx = testContext()
        val dao = FakeSpamShieldDao()
        val lookup = FakeContactLookup()
        val engine = SpamScoringEngine(dao, settings, lookup)
        val analyzer = CallHistoryAnalyzer(ctx, dao, settings, lookup, engine)
        val syncRepo = SyncRepository(dao, settings)
        val featureRepo = FeatureRepository(dao, settings)
        val vm = MainViewModel(ctx, dao, settings, syncRepo, engine, analyzer, featureRepo)

        val rulesBefore = dao.countRules()
        vm.addCustomRegexOrPrefixRule("bad id", "Inválida", "[unclosed", 80)
        assertNotNull(vm.featureError.value)
        assertEquals(rulesBefore, dao.countRules())
        vm.clearFeatureError()
        assertNull(vm.featureError.value)

        vm.addCustomRegexOrPrefixRule("sp telemarketing!", "Bloqueio SP", "^\\+55113003\\d{4}$", 999)
        kotlinx.coroutines.delay(80)
        val savedRule = dao.getEnabledRules().find { it.ruleId == "SP_TELEMARKETING_" }
        assertNotNull(savedRule)
        assertEquals(200, savedRule?.weight)

        val qBefore = dao.getAllQuarantines().size
        vm.addManualQuarantinePrefix("319", "", 80)
        kotlinx.coroutines.delay(40)
        assertEquals(qBefore, dao.getAllQuarantines().size)

        vm.addManualQuarantinePrefix("(31) 3222-1", "", 85)
        kotlinx.coroutines.delay(80)
        val manualFixo = dao.getAllQuarantines().find { it.prefix7 == "3132221" }
        assertNotNull(manualFixo)
        assertEquals("Fixo", manualFixo?.carrierGuess)

        QuarantinePrefixIndex.replaceActive(dao.getAllQuarantines(), System.currentTimeMillis())
        val simBlock = vm.simulateTestCall("03141011230", "FAILED")
        assertTrue(simBlock.startsWith("Bloqueio na simulação."))

        settings.setShadowMode(true)
        val simShadow = vm.simulateTestCall("03141011230", "FAILED")
        assertTrue(simShadow.startsWith("Liberação em modo sombra na simulação."))
        settings.setShadowMode(false)

        val simAllow = vm.simulateTestCall("031991112222", "PASSED")
        assertTrue(simAllow.startsWith("Liberação na simulação."))

        vm.checkApkUpdate()
        kotlinx.coroutines.delay(100)
        assertTrue(vm.apkUpdateState.value.hasNewerVersion)
        assertTrue(vm.apkUpdateState.value.statusMessage.contains("5.0 MB"))

        val exportCallId = "00000000-0000-4000-8000-000000000501"
        val now = 1_700_000_900_000L
        dao.insertTelemetry(
            CallTelemetryEntity(
                id = exportCallId,
                timestamp = now,
                rawNumber = "031\"41011230",
                normalizedNumber = "+553141011230",
                ddd = "31",
                prefix7 = "31410112",
                carrierGuess = "Operadora \"X\"",
                lineType = "FIXED_OR_VOIP",
                stirShakenStatus = "FAILED",
                finalScore = 165,
                thresholdApplied = 70,
                outcome = "BLOCKED",
                category = "SPOOFING_STIR_FAILED",
                processingLatencyMs = 1.9,
                shadowModeActive = false,
                triggeredRulesJson = """[{"rule_id":"STIR_SHAKEN_FAILED","weight":85}]"""
            )
        )
        val policy = ScreeningPolicyDecision("BLOCK", "BLOCK", "NORMAL_SCREENING")
        dao.insertDecisionEvent(
            CallDecisionEventEntity(
                eventId = "00000000-0000-4000-8000-000000000502",
                callId = exportCallId,
                recordedAt = now,
                eventType = "SCREENING_RESPONSE_SUBMITTED",
                source = "CALL_SCREENING_SERVICE",
                appVersion = "1.4.1 (21)",
                detailsJson = FeatureJournal.json.encodeToString(DecisionDetails(disallowCall = true, rejectCall = true, policy = policy))
            )
        )
        val featEvent = FeatureJournal.event(
            "WAIT_FEEDBACK",
            "AUDIT_USER",
            "00000000-0000-4000-8000-000000000503",
            now,
            FeaturePayload(reason = "SPAM_CONFIRMED", decisionEventId = "00000000-0000-4000-8000-000000000502"),
            callId = exportCallId
        )
        dao.insertFeatureEvents(listOf(featEvent))

        val (jsonFile, csvFile) = vm.exportTelemetryFiles()
        val summaryFile = File(jsonFile.parentFile, "spamshield_telemetry_summary.json")
        assertTrue(jsonFile.exists())
        assertTrue(csvFile.exists())
        assertTrue(summaryFile.exists())

        val summaryObj = Json.parseToJsonElement(summaryFile.readText()).jsonObject
        val exportId = summaryObj["exportMetadata"]!!.jsonObject["exportId"]!!.jsonPrimitive.content
        val exportedArray = Json.parseToJsonElement(jsonFile.readText()).jsonArray
        assertTrue(exportedArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == exportCallId })

        val csvLines = csvFile.readLines().filter { it.isNotBlank() }
        assertEquals(37, csvLines.first().split(',').size)
        val targetLine = csvLines.first { it.startsWith("$exportCallId,") }
        assertTrue(targetLine.contains("\"Operadora \"\"X\"\"\""))
        assertTrue(targetLine.contains(exportId))
        assertTrue(targetLine.contains(featEvent.eventId))
    }
}
