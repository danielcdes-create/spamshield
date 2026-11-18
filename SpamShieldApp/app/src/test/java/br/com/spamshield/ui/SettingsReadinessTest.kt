package br.com.spamshield.ui

import br.com.spamshield.data.local.SpamShieldSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReadinessTest {
    @Test fun acceptedDisclosureNeverAppearsUnacceptedWhileDiskReadIsPending() = runTest {
        val stored = CompletableDeferred<SpamShieldSettings>()
        val state = flow { emit(stored.await()) }.asSettingsUiState(backgroundScope)
        val observed = mutableListOf<SpamShieldSettings?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.toList(observed) }
        assertNull(state.value)
        assertEquals(listOf<SpamShieldSettings?>(null), observed)

        stored.complete(SpamShieldSettings(localDataDisclosureAccepted = true, remoteSyncEnabled = true))
        runCurrent()
        assertTrue(state.value!!.localDataDisclosureAccepted)
        assertTrue(state.value!!.remoteSyncEnabled)
        assertTrue(observed.filterNotNull().all { it.localDataDisclosureAccepted })
    }

    @Test fun newInstallationStillRequiresDisclosureAfterTheActualPreferencesLoad() = runTest {
        val stored = CompletableDeferred<SpamShieldSettings>()
        val state = flow { emit(stored.await()) }.asSettingsUiState(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        assertNull(state.value)
        stored.complete(SpamShieldSettings())
        runCurrent()
        assertFalse(state.value!!.localDataDisclosureAccepted)
        assertFalse(state.value!!.remoteSyncEnabled)
    }

    @Test fun resumingAfterSubscriptionStopsKeepsTheLastLoadedPrivacyChoice() = runTest {
        val saved = SpamShieldSettings(localDataDisclosureAccepted = true)
        val state = flow { emit(saved) }.asSettingsUiState(backgroundScope)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        runCurrent()
        assertEquals(saved, state.value)
        first.cancel()
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(saved, state.value)

        val observed = mutableListOf<SpamShieldSettings?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.toList(observed) }
        runCurrent()
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it?.localDataDisclosureAccepted == true })
    }
}
