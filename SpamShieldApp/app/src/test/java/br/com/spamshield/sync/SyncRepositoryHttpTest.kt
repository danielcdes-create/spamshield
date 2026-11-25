package br.com.spamshield.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.SyncMutationEntity
import br.com.spamshield.data.remote.FeedbackPatchRequestDto
import br.com.spamshield.domain.FakeSpamShieldDao
import br.com.spamshield.domain.PrefixQuarantineMatch
import br.com.spamshield.domain.QuarantinePrefixIndex
import br.com.spamshield.domain.TelemetrySyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class TestHttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

data class TestHttpResponse(
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: ByteArray = ByteArray(0)
) {
    companion object {
        fun json(status: Int, jsonBody: String) =
            TestHttpResponse(status, "application/json", jsonBody.toByteArray(Charsets.UTF_8))
    }
}

class TestLoopbackHttpServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val handlers = ConcurrentHashMap<String, (TestHttpRequest) -> TestHttpResponse>()
    @Volatile
    private var running = true

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        executor.submit {
            while (running && !serverSocket.isClosed) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                executor.submit { handleClient(client) }
            }
        }
    }

    fun route(pathPrefix: String, handler: (TestHttpRequest) -> TestHttpResponse) {
        handlers[pathPrefix] = handler
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (b == '\n'.code) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input)?.substringBefore(';')?.trim() ?: break
            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            if (chunkSize == 0) {
                readAsciiLine(input)
                break
            }
            out.write(readExact(input, chunkSize))
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val fullTarget = parts[1]
            val path = fullTarget.substringBefore('?')
            val query = fullTarget.substringAfter('?', "")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                        line.substring(colon + 1).trim()
                }
            }

            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                else -> headers["content-length"]?.toIntOrNull()?.takeIf { it > 0 }?.let { readExact(input, it) } ?: ByteArray(0)
            }

            val req = TestHttpRequest(method, path, query, headers, body)
            val matched = handlers.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { path == it.key || path.startsWith(it.key) }
            val resp = runCatching {
                matched?.value?.invoke(req) ?: TestHttpResponse.json(404, """{"error":"not_found"}""")
            }.getOrElse {
                TestHttpResponse.json(500, """{"error":"server_error"}""")
            }

            val reason = when (resp.status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                409 -> "Conflict"
                503 -> "Service Unavailable"
                else -> "Response"
            }
            val head = buildString {
                append("HTTP/1.1 ${resp.status} $reason\r\n")
                append("Content-Type: ${resp.contentType}\r\n")
                append("Content-Length: ${resp.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            val output = sock.getOutputStream()
            output.write(head)
            output.write(resp.body)
            output.flush()
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }
}

class SyncRepositoryHttpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: TestLoopbackHttpServer
    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore

    @Before
    fun setUp() {
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
        server = TestLoopbackHttpServer()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefsFile = File(tempFolder.newFolder("datastore"), "test.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile }
        settings = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        server.close()
        storeScope.cancel()
        QuarantinePrefixIndex.clear()
        TelemetrySyncScheduler.resetForTests()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `performFullSync uploads telemetry, features and outbox, rolls back rejected auto quarantine and pulls snapshot`() = runBlocking {
        val dao = FakeSpamShieldDao().apply {
            quarantines.clear()
            anatelPrefixes.clear()
        }
        settings.setTailscaleServerUrl(server.baseUrl)
        settings.setApiBearerToken("secret-test-token")
        settings.setRemoteSyncEnabled(true)
        settings.setUserDdd("11")
        settings.transitionExpectedWindow(at = 1_700_000_000_000L, selectorId = "00000000-0000-4000-8000-000000000010", minutes = 15)

        val now = System.currentTimeMillis()
        val acceptedCall = CallTelemetryEntity(
            id = "call-accepted",
            timestamp = now - 5_000L,
            rawNumber = "01140031234",
            normalizedNumber = "+551140031234",
            ddd = "11",
            prefix7 = "11400312",
            carrierGuess = "VoIP",
            lineType = "FIXED_OR_VOIP",
            stirShakenStatus = "NOT_VERIFIED",
            finalScore = 95,
            thresholdApplied = 70,
            outcome = "BLOCKED",
            category = "ROBOCALL_PREFIX_CLUSTER",
            processingLatencyMs = 1.4,
            shadowModeActive = false,
            triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25}]""",
            syncStatus = "PENDING"
        )
        val deferredCall = acceptedCall.copy(
            id = "call-deferred",
            timestamp = now - 4_000L,
            normalizedNumber = "+551140039999"
        )
        val duplicateHistory = acceptedCall.copy(
            id = "history-dup",
            deviceId = "s23-ultra-history",
            timestamp = acceptedCall.timestamp + 2_000L,
            syncStatus = "PENDING"
        )
        dao.insertTelemetry(acceptedCall)
        dao.insertTelemetry(deferredCall)
        dao.insertTelemetry(duplicateHistory)
        dao.insertDecisionEvent(
            CallDecisionEventEntity(
                eventId = "evt-accepted",
                callId = "call-accepted",
                recordedAt = acceptedCall.timestamp,
                eventType = "SCREENING_DECISION",
                source = "CALL_SCREENING_SERVICE",
                appVersion = "1.4.1 (21)",
                detailsJson = """{"score":95}""",
                syncStatus = "PENDING"
            )
        )
        dao.insertDecisionEvent(
            CallDecisionEventEntity(
                eventId = "evt-deferred",
                callId = "call-deferred",
                recordedAt = deferredCall.timestamp,
                eventType = "SCREENING_DECISION",
                source = "CALL_SCREENING_SERVICE",
                appVersion = "1.4.1 (21)",
                detailsJson = """{"score":95}""",
                syncStatus = "PENDING"
            )
        )

        val json = Json { encodeDefaults = true }
        dao.enqueueSyncMutation(
            SyncMutationEntity(
                mutationId = "mut-fb-ok",
                kind = "FEEDBACK",
                targetId = "call-accepted",
                payloadJson = json.encodeToString(FeedbackPatchRequestDto("CONFIRMED_SPAM", "mut-fb-ok", now)),
                createdAt = now
            )
        )
        dao.enqueueSyncMutation(
            SyncMutationEntity(
                mutationId = "mut-fb-deferred",
                kind = "FEEDBACK",
                targetId = "call-deferred",
                payloadJson = json.encodeToString(FeedbackPatchRequestDto("CONFIRMED_SPAM", "mut-fb-deferred", now)),
                createdAt = now
            )
        )
        val rejectedAutoEntity = PrefixQuarantineEntity(
            prefix7 = "11988881",
            ddd = "11",
            carrierGuess = "TIM",
            hitCount = 1,
            penaltyWeight = 70,
            reason = "DIALER_REJECTED",
            firstSeenAt = now,
            lastSeenAt = now,
            expiresAt = now + 86_400_000L
        )
        dao.installAutomaticQuarantine(rejectedAutoEntity, now)
        val manualRule = ActiveRuleEntity(
            ruleId = "CUSTOM_SP_TEST",
            name = "Teste SP",
            ruleType = "REGEX_MATCH",
            pattern = "^\\+55119999\\d+$",
            weight = 75,
            priority = 25,
            enabled = true,
            updatedAt = now
        )
        dao.persistRuleUpdate(
            manualRule,
            SyncMutationEntity("mut-rule-1", "RULE_UPSERT", manualRule.ruleId, json.encodeToString(manualRule), now)
        )
        dao.deleteQuarantineByPrefix("1130031")

        val seenRequests = CopyOnWriteArrayList<String>()
        val authHeaders = CopyOnWriteArrayList<String>()

        server.route("/api/v1/telemetry/batch") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}"
            TestHttpResponse.json(
                200,
                """{"accepted_ids":["call-accepted","call-deferred","history-dup"],"accepted_event_ids":["evt-accepted"],"quarantine_updates":0}"""
            )
        }
        server.route("/api/v1/telemetry/features/batch") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}"
            val reqJson = req.body.toString(Charsets.UTF_8)
            val eventId = Regex(""""event_id"\s*:\s*"([^"]+)"""").find(reqJson)!!.groupValues[1]
            TestHttpResponse.json(200, """{"accepted_event_ids":["$eventId"],"conflicting_event_ids":[]}""")
        }
        server.route("/api/v1/telemetry/call-accepted/feedback") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}"
            TestHttpResponse.json(200, """{"id":"call-accepted","user_feedback":"CONFIRMED_SPAM","recalibrated_rules":[]}""")
        }
        server.route("/api/v1/quarantine") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}?${req.query}"
            if (req.method == "DELETE") {
                TestHttpResponse.json(200, """{"deleted":true}""")
            } else {
                TestHttpResponse.json(200, """{"applied":false}""")
            }
        }
        server.route("/api/v1/rules") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}"
            TestHttpResponse(200, "application/json", req.body)
        }
        server.route("/api/v1/sync/payload") { req ->
            authHeaders += req.header("authorization").orEmpty()
            seenRequests += "${req.method} ${req.path}?${req.query}"
            val futureIso = java.time.Instant.ofEpochMilli(now + 3_600_000L).toString()
            val nowIso = java.time.Instant.ofEpochMilli(now).toString()
            TestHttpResponse.json(
                200,
                """
                {
                  "quarantine_snapshot_complete": true,
                  "quarantine_deleted_prefixes": [],
                  "active_rules": [
                    {"rule_id":"REMOTE_RULE_1","name":"Remota","rule_type":"HEURISTIC_WEIGHT","pattern":null,"weight":50,"priority":15,"enabled":true,"updated_at":"$nowIso"}
                  ],
                  "prefix_quarantine": [
                    {"prefix_7":"1148581","ddd":"11","carrier_guess":"VoIP","distinct_numbers_seen":[],"hit_count":3,"penalty_weight":80,"reason":"KNOWN_BLACKLIST","first_seen_at":"$nowIso","last_seen_at":"$nowIso","expires_at":"$futureIso"}
                  ],
                  "anatel_numbering_plan": [
                    {"prefix_7":"1148581","ddd":"11","carrier":"VoIP","line_type":"FIXED_OR_VOIP","is_corporate_pabx_range":true,"updated_at":"$nowIso"}
                  ],
                  "server_timestamp": 1795000123456
                }
                """.trimIndent()
            )
        }

        val repo = SyncRepository(dao, settings)
        val outcome = repo.performFullSync()

        assertFalse(outcome.success)
        assertTrue(outcome.serverOnline)
        assertFalse(outcome.retryable)
        assertTrue(authHeaders.all { it == "Bearer secret-test-token" })

        assertTrue(seenRequests.any { it == "PATCH /api/v1/telemetry/call-accepted/feedback" })
        assertFalse(seenRequests.any { it.contains("call-deferred/feedback") })
        val remainingKinds = dao.getSyncMutations().map { it.kind to it.targetId }.toSet()
        assertEquals(setOf("FEEDBACK" to "call-deferred"), remainingKinds)

        assertNull(dao.getActiveQuarantineForPrefix("11988881", now))
        assertNotNull(dao.getActiveQuarantineForPrefix("1148581", now))
        assertEquals(PrefixQuarantineMatch.BROAD7, QuarantinePrefixIndex.match("11485819", now))

        assertNull(dao.getTelemetryById("history-dup"))
        assertTrue(dao.getEnabledRules().any { it.ruleId == "REMOTE_RULE_1" })
        assertNotNull(dao.getAnatelPrefix("1148581"))
        assertEquals(1_795_000_123_456L, settings.getSnapshot().lastSyncTimestamp)
        assertTrue(dao.getPendingFeatureEvents().isEmpty())
    }

    @Test
    fun `performFullSync aborts cleanly when remoteSyncEnabled is revoked mid-flight`() = runBlocking {
        val dao = FakeSpamShieldDao()
        settings.setTailscaleServerUrl(server.baseUrl)
        settings.setApiBearerToken("token-1")
        settings.setRemoteSyncEnabled(true)

        dao.insertTelemetry(
            CallTelemetryEntity(
                id = "call-mid-revoke",
                timestamp = 1_700_000_000_000L,
                rawNumber = "31999990000",
                normalizedNumber = "+5531999990000",
                ddd = "31",
                prefix7 = "31999990",
                carrierGuess = "Vivo",
                lineType = "MOBILE",
                stirShakenStatus = "NOT_VERIFIED",
                finalScore = 45,
                thresholdApplied = 70,
                outcome = "ALLOWED_LOW_SCORE",
                category = "UNKNOWN_UNVERIFIED",
                processingLatencyMs = 1.0,
                shadowModeActive = false,
                triggeredRulesJson = "[]",
                syncStatus = "PENDING"
            )
        )

        var payloadRequested = false
        server.route("/api/v1/telemetry/batch") {
            runBlocking { settings.setRemoteSyncEnabled(false) }
            TestHttpResponse.json(200, """{"accepted_ids":["call-mid-revoke"],"accepted_event_ids":[],"quarantine_updates":0}""")
        }
        server.route("/api/v1/sync/payload") {
            payloadRequested = true
            TestHttpResponse.json(200, """{}""")
        }

        val repo = SyncRepository(dao, settings)
        val outcome = repo.performFullSync()
        assertTrue(outcome.success)
        assertTrue(outcome.message.contains("desativada"))
        assertFalse(payloadRequested)
    }

    @Test
    fun `downloadApkUpdate verifies SHA-256 checksum and deletes tampered binary`() = runBlocking {
        val dao = FakeSpamShieldDao()
        settings.setTailscaleServerUrl(server.baseUrl)
        settings.setApiBearerToken("ota-token")

        val validBytes = "SpamShield-Signed-APK-Binary-Payload-v1.4.1".toByteArray()
        val validChecksum = sha256(validBytes)
        var servedBytes = validBytes

        server.route("/api/v1/apk/info") {
            TestHttpResponse.json(
                200,
                """{"version_code":22,"version_name":"1.4.2","size_bytes":${validBytes.size},"sha256_checksum":"$validChecksum","download_path":"/apk"}"""
            )
        }
        server.route("/apk") {
            TestHttpResponse(200, "application/vnd.android.package-archive", servedBytes)
        }

        val repo = SyncRepository(dao, settings)
        val info = repo.fetchRemoteApkInfo()
        assertNotNull(info)
        assertEquals(22, info?.version_code)

        val updatesDir = tempFolder.newFolder("updates")
        val downloaded = repo.downloadApkUpdate(updatesDir)
        assertNotNull(downloaded)
        assertTrue(downloaded!!.exists())
        assertEquals(validBytes.toList(), downloaded.readBytes().toList())

        servedBytes = "Tampered-MITM-APK-Payload".toByteArray()
        val tamperedDir = tempFolder.newFolder("updates-tampered")
        val rejected = repo.downloadApkUpdate(tamperedDir)
        assertNull(rejected)
        assertFalse(File(tamperedDir, "spamshield-update.apk").exists())
    }

    @Test
    fun `checkServerReachable, triggerProvidersSync and FALSE_POSITIVE feedback clear both slash 8 and slash 7 quarantines`() = runBlocking {
        val dao = FakeSpamShieldDao().apply { quarantines.clear() }
        val now = System.currentTimeMillis()
        settings.setTailscaleServerUrl(server.baseUrl)
        settings.setApiBearerToken("sync-token")
        settings.setRemoteSyncEnabled(true)

        server.route("/api/v1/health") {
            TestHttpResponse.json(200, """{"status":"ok","service":"spamshield-server","mongodb_connected":true,"tailscale_ip":"127.0.0.1"}""")
        }
        server.route("/api/v1/providers/sync") {
            TestHttpResponse.json(200, """{"anatel_prefixes_synced":35,"blacklist_clusters_synced":11,"rules_synced":3,"sources":["Anatel"]}""")
        }
        server.route("/api/v1/sync/payload") {
            TestHttpResponse.json(
                200,
                """{"quarantine_snapshot_complete":true,"quarantine_deleted_prefixes":[],"active_rules":[],"prefix_quarantine":[],"anatel_numbering_plan":[],"server_timestamp":1795000999000}"""
            )
        }

        val repo = SyncRepository(dao, settings)
        assertTrue(repo.checkServerReachable(server.baseUrl))
        assertFalse(repo.checkServerReachable("http://example.com:8080"))

        val providerRes = repo.triggerProvidersSync()
        assertTrue(providerRes.success)
        assertTrue(providerRes.message.contains("35 faixas Anatel"))

        dao.upsertQuarantine(
            PrefixQuarantineEntity("31982042", "31", "TIM", "[]", 1, 70, "DIALER_REJECTED", now, now, now + 86_400_000L)
        )
        dao.upsertQuarantine(
            PrefixQuarantineEntity("3198204", "31", "TIM", "[]", 2, 60, "ROBOCALL_PREFIX_CLUSTER", now, now, now + 86_400_000L)
        )
        QuarantinePrefixIndex.replaceAll(listOf("31982042", "3198204"))
        dao.insertTelemetry(
            CallTelemetryEntity(
                id = "call-fp-1",
                timestamp = now,
                rawNumber = "031982042364",
                normalizedNumber = "+5531982042364",
                ddd = "31",
                prefix7 = "31982042",
                carrierGuess = "TIM",
                lineType = "MOBILE",
                stirShakenStatus = "NOT_VERIFIED",
                finalScore = 115,
                thresholdApplied = 70,
                outcome = "BLOCKED",
                category = "ROBOCALL_PREFIX_CLUSTER",
                processingLatencyMs = 1.1,
                shadowModeActive = false,
                triggeredRulesJson = """[{"rule_id":"NOT_IN_CONTACTS","weight":25},{"rule_id":"PREFIX_QUARANTINE_HIT","weight":70}]"""
            )
        )

        repo.submitFeedback("call-fp-1", "FALSE_POSITIVE", "31982042")
        val updated = dao.getTelemetryById("call-fp-1")!!
        assertEquals("FALSE_POSITIVE", updated.userFeedback)
        assertEquals("ALLOWED_CONTACT", updated.outcome)
        assertEquals("LEGITIMATE", updated.category)
        assertEquals(0, updated.finalScore)
        assertNull(dao.getActiveQuarantineForPrefix("31982042", now))
        assertNull(dao.getActiveQuarantineForPrefix("3198204", now))
        assertEquals(PrefixQuarantineMatch.NONE, QuarantinePrefixIndex.match("31982042", now))
    }
}
