package br.com.spamshield

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import br.com.spamshield.domain.TelemetrySyncScheduler
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpamShieldApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        TelemetrySyncScheduler.install { SyncWorker.enqueueOneTimeSync(this) }
        // Existing work is gated by persisted consent in SyncRepository. New periodic work
        // is scheduled only when the user explicitly enables remote synchronization.
    }
}
