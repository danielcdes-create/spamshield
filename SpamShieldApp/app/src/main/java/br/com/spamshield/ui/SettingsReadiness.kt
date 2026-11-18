package br.com.spamshield.ui

import br.com.spamshield.data.local.SpamShieldSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Null means unread: privacy choices must come from persisted preferences before rendering. */
internal fun Flow<SpamShieldSettings>.asSettingsUiState(scope: CoroutineScope): StateFlow<SpamShieldSettings?> =
    stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
