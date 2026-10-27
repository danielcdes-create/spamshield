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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spamshield_prefs")

data class SpamShieldSettings(
    val shadowModeEnabled: Boolean = false,
    val blockThresholdScore: Int = 70,
    val tailscaleServerUrl: String = "http://100.83.253.15:8080",
    val apiBearerToken: String = "",
    val hideBlockedFromSystemCallLog: Boolean = false,
    val userDdd: String = "31",
    val lastSyncTimestamp: Long = 0L
)

@Singleton
class SettingsDataStore internal constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") isTest: Boolean
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, false)

    // Parameterless constructor for JVM unit tests
    constructor() : this(null, true)

    companion object {
        val KEY_SHADOW_MODE = booleanPreferencesKey("shadow_mode_enabled")
        val KEY_BLOCK_THRESHOLD = intPreferencesKey("block_threshold_score")
        val KEY_TAILSCALE_URL = stringPreferencesKey("tailscale_server_url")
        val KEY_API_BEARER = stringPreferencesKey("api_bearer_token")
        val KEY_HIDE_FROM_CALL_LOG = booleanPreferencesKey("hide_blocked_from_system_call_log")
        val KEY_USER_DDD = stringPreferencesKey("user_ddd")
        val KEY_LAST_SYNC_TS = longPreferencesKey("last_sync_timestamp")
    }

    val settingsFlow: Flow<SpamShieldSettings> = context?.dataStore?.data?.map { prefs ->
        SpamShieldSettings(
            shadowModeEnabled = prefs[KEY_SHADOW_MODE] ?: false,
            blockThresholdScore = prefs[KEY_BLOCK_THRESHOLD] ?: 70,
            tailscaleServerUrl = prefs[KEY_TAILSCALE_URL] ?: "http://100.83.253.15:8080",
            apiBearerToken = prefs[KEY_API_BEARER] ?: "",
            hideBlockedFromSystemCallLog = prefs[KEY_HIDE_FROM_CALL_LOG] ?: false,
            userDdd = prefs[KEY_USER_DDD] ?: "31",
            lastSyncTimestamp = prefs[KEY_LAST_SYNC_TS] ?: 0L
        )
    } ?: flowOf(SpamShieldSettings())

    suspend fun getSnapshot(): SpamShieldSettings = settingsFlow.first()

    suspend fun setShadowMode(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_SHADOW_MODE] = enabled }
    }

    suspend fun setBlockThreshold(threshold: Int) {
        context?.dataStore?.edit { it[KEY_BLOCK_THRESHOLD] = threshold.coerceIn(20, 150) }
    }

    suspend fun setTailscaleServerUrl(url: String) {
        context?.dataStore?.edit { it[KEY_TAILSCALE_URL] = url.trim().removeSuffix("/") }
    }

    suspend fun setApiBearerToken(token: String) {
        context?.dataStore?.edit { it[KEY_API_BEARER] = token.trim() }
    }

    suspend fun setHideBlockedFromSystemCallLog(hide: Boolean) {
        context?.dataStore?.edit { it[KEY_HIDE_FROM_CALL_LOG] = hide }
    }

    suspend fun setUserDdd(ddd: String) {
        context?.dataStore?.edit { it[KEY_USER_DDD] = ddd.filter { c -> c.isDigit() }.take(2) }
    }

    suspend fun setLastSyncTimestamp(ts: Long) {
        context?.dataStore?.edit { it[KEY_LAST_SYNC_TS] = ts }
    }
}
