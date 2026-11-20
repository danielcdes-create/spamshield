package br.com.spamshield.sync

import br.com.spamshield.data.remote.FeatureBatchAck
import br.com.spamshield.data.remote.FeatureEventRemoteDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class FeatureUploadTest {
    @Test
    fun acknowledgesOnlySubmittedIdsAndIsolatesConflictsAndSizeErrors() = runTest {
        val events = listOf("accepted", "conflict", "oversize", "partial").map {
            FeatureEventRemoteDto(it, null, "id", "WAIT_SELECTOR_OPENED", "DASHBOARD_SHIELD", 1, 1, "fixture", 1, JsonObject(emptyMap()))
        }
        val acknowledged = mutableSetOf<String>()
        val persisted = mutableSetOf<String>()
        suspend fun send(batch: List<FeatureEventRemoteDto>): FeatureBatchAck {
            val ids = batch.map { it.event_id }
            fun error(code: Int): Nothing = throw HttpException(Response.error<FeatureBatchAck>(code, "".toResponseBody()))
            if ("oversize" in ids) error(413)
            if (ids == listOf("conflict")) error(409)
            val accepted = ids.filter { it == "accepted" }
            persisted.addAll(accepted)
            return FeatureBatchAck(accepted + listOf("conflict", "created-during-upload"), ids.filter { it == "conflict" })
        }
        repeat(2) {
            assertEquals(3, uploadFeatureBatch(events, ::send) { ids -> acknowledged.addAll(ids) })
        }
        assertEquals(setOf("accepted"), acknowledged)
        assertEquals(acknowledged, persisted)
        assertEquals(1, uploadFeatureBatch(listOf(events[1]), ::send) { error("unexpected ACK") })
    }
}
