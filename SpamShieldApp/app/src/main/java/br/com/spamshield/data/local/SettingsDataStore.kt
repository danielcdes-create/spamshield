package br.com.spamshield.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import br.com.spamshield.domain.ExpectedWindow
import br.com.spamshield.domain.FeatureJournal
import br.com.spamshield.domain.FeaturePayload
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.local.dao.SpamShieldDao
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spamshield_prefs")

data class SpamShieldSettings(
    val shadowModeEnabled: Boolean = false,
    val blockThresholdScore: Int = 70,
    val tailscaleServerUrl: String = "http://100.83.253.15:8080",
    val apiBearerToken: String = "",
    val hideBlockedFromSystemCallLog: Boolean = false,
    val userDdd: String = "31",
    val lastSyncTimestamp: Long = 0L,
    val localDataDisclosureAccepted: Boolean = false,
    val remoteSyncEnabled: Boolean = false,
    val expectedWindow: ExpectedWindow? = null
)

@Singleton
class SettingsDataStore internal constructor(
    private val store: DataStore<Preferences>?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.dataStore)

    // Parameterless constructor for JVM unit tests
    constructor() : this(null)

    companion object {
        val KEY_SHADOW_MODE = booleanPreferencesKey("shadow_mode_enabled")
        val KEY_BLOCK_THRESHOLD = intPreferencesKey("block_threshold_score")
        val KEY_TAILSCALE_URL = stringPreferencesKey("tailscale_server_url")
        val KEY_API_BEARER = stringPreferencesKey("api_bearer_token")
        val KEY_HIDE_FROM_CALL_LOG = booleanPreferencesKey("hide_blocked_from_system_call_log")
        val KEY_USER_DDD = stringPreferencesKey("user_ddd")
        val KEY_LAST_SYNC_TS = longPreferencesKey("last_sync_timestamp")
        val KEY_LOCAL_DISCLOSURE = booleanPreferencesKey("local_data_disclosure_v1")
        val KEY_REMOTE_SYNC = booleanPreferencesKey("remote_sync_consent_v1")
        private val KEY_EXPECTED_WINDOW = stringPreferencesKey("expected_window")
        private val KEY_WINDOW_OUTBOX = stringPreferencesKey("expected_window_outbox")
    }

    val settingsFlow: Flow<SpamShieldSettings> = store?.data?.map { prefs ->
        SpamShieldSettings(
            shadowModeEnabled = prefs[KEY_SHADOW_MODE] ?: false,
            blockThresholdScore = prefs[KEY_BLOCK_THRESHOLD] ?: 70,
            tailscaleServerUrl = prefs[KEY_TAILSCALE_URL] ?: "http://100.83.253.15:8080",
            apiBearerToken = prefs[KEY_API_BEARER] ?: "",
            hideBlockedFromSystemCallLog = prefs[KEY_HIDE_FROM_CALL_LOG] ?: false,
            userDdd = prefs[KEY_USER_DDD] ?: "31",
            lastSyncTimestamp = prefs[KEY_LAST_SYNC_TS] ?: 0L,
            localDataDisclosureAccepted = prefs[KEY_LOCAL_DISCLOSURE] ?: false,
            remoteSyncEnabled = prefs[KEY_REMOTE_SYNC] ?: false,
            expectedWindow = prefs.window()
        )
    } ?: flowOf(SpamShieldSettings())

    suspend fun getSnapshot(): SpamShieldSettings = settingsFlow.first()

    private fun Preferences.window(): ExpectedWindow? =
        this[KEY_EXPECTED_WINDOW]?.let { FeatureJournal.json.decodeFromString<ExpectedWindow>(it) }

    private fun Preferences.windowOutbox(): List<FeatureEventEntity> =
        this[KEY_WINDOW_OUTBOX]?.let { FeatureJournal.json.decodeFromString<List<FeatureEventEntity>>(it) }.orEmpty()

    suspend fun transitionExpectedWindow(
        at: Long, selectorId: String? = null, minutes: Int? = null,
        keepExact: Boolean = true, restore: Boolean = false
    ) {
        store?.edit { prefs ->
            val previous = prefs.window()
            val events = prefs.windowOutbox().toMutableList()
            fun append(type: String, window: ExpectedWindow, reason: String, effectiveAt: Long, previousId: String? = null) {
                events += FeatureJournal.event(type, FeatureJournal.DASHBOARD_SHIELD, window.id, at,
                    FeaturePayload(window = window, selectorId = selectorId, reason = reason, previousId = previousId),
                    effectiveAt = effectiveAt, key = window.id)
            }
            if (previous != null && previous.expiresAt <= at) {
                append("WAIT_EXPIRED", previous, "TIME_LIMIT", previous.expiresAt)
                prefs.remove(KEY_EXPECTED_WINDOW)
            }
            val active = previous?.takeIf { it.activeAt(at) }
            if (minutes != null) {
                val next = ExpectedWindow.start(at, minutes, keepExact)
                append(if (active == null) "WAIT_ACTIVATED" else "WAIT_RENEWED", next,
                    if (active == null) "SELECTED_DURATION" else "REPLACED_ACTIVE_WINDOW", at, active?.id)
                prefs[KEY_EXPECTED_WINDOW] = FeatureJournal.json.encodeToString(next)
            } else if (restore && active != null) {
                append("WAIT_RESTORED", active, "MANUAL_RESTORE", at)
                prefs.remove(KEY_EXPECTED_WINDOW)
            }
            if (events != prefs.windowOutbox()) prefs[KEY_WINDOW_OUTBOX] = FeatureJournal.json.encodeToString(events)
        }
    }

    suspend fun flushWindowJournal(dao: SpamShieldDao): Boolean {
        val store = store ?: return false
        val snapshot = store.data.first().windowOutbox()
        if (snapshot.isEmpty()) return false
        dao.insertFeatureEvents(snapshot)
        val ids = snapshot.map { it.eventId }.toSet()
        store.edit { prefs ->
            val pending = prefs.windowOutbox().filterNot { it.eventId in ids }
            if (pending.isEmpty()) prefs.remove(KEY_WINDOW_OUTBOX)
            else prefs[KEY_WINDOW_OUTBOX] = FeatureJournal.json.encodeToString(pending)
        }
        return true
    }

    suspend fun acceptLocalDataDisclosure() {
        store?.edit { it[KEY_LOCAL_DISCLOSURE] = true }
    }

    suspend fun setRemoteSyncEnabled(enabled: Boolean) {
        store?.edit { it[KEY_REMOTE_SYNC] = enabled }
    }

    suspend fun setShadowMode(enabled: Boolean) {
        store?.edit { it[KEY_SHADOW_MODE] = enabled }
    }

    suspend fun setBlockThreshold(threshold: Int) {
        store?.edit { it[KEY_BLOCK_THRESHOLD] = threshold.coerceIn(20, 150) }
    }

    suspend fun setTailscaleServerUrl(url: String) {
        val normalized = url.trim().removeSuffix("/")
        store?.edit {
            val previous = it[KEY_TAILSCALE_URL] ?: SpamShieldSettings().tailscaleServerUrl
            if (previous != normalized) it[KEY_REMOTE_SYNC] = false
            it[KEY_TAILSCALE_URL] = normalized
        }
    }

    suspend fun setApiBearerToken(token: String) {
        store?.edit { it[KEY_API_BEARER] = token.trim() }
    }

    suspend fun setHideBlockedFromSystemCallLog(hide: Boolean) {
        store?.edit { it[KEY_HIDE_FROM_CALL_LOG] = hide }
    }

    suspend fun setUserDdd(ddd: String) {
        store?.edit { it[KEY_USER_DDD] = ddd.filter { c -> c.isDigit() }.take(2) }
    }

    suspend fun setLastSyncTimestamp(ts: Long) {
        store?.edit { it[KEY_LAST_SYNC_TS] = ts }
    }
}
