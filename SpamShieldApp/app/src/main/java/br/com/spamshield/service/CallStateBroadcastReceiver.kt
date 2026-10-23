package br.com.spamshield.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.OutboundGraceEntity
import br.com.spamshield.domain.PhoneNormalizer
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Monitors call termination (`EXTRA_STATE_IDLE`) to:
 * 1. Detect "Ping Calls" (answered incoming call from unknown number lasting 1..4 seconds).
 * 2. Record outbound calls in `outbound_grace` with a 2-hour PBX return immunity window.
 */
@AndroidEntryPoint
class CallStateBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var spamScoringEngine: SpamScoringEngine

    @Inject
    lateinit var dao: SpamShieldDao

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SpamScoringEngine"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                // Allow system CallLog a brief moment to commit the finished call
                delay(1200L)
                inspectLatestSystemCallLog(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Post-call log inspection skipped: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun inspectLatestSystemCallLog(context: Context) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val rawNumber = cursor.getString(0) ?: return
            val callType = cursor.getInt(1)
            val durationSec = cursor.getInt(2)
            val now = System.currentTimeMillis()

            val normalized = PhoneNormalizer.normalize(rawNumber)
            if (normalized.normalizedNumber.isEmpty()) return

            when (callType) {
                CallLog.Calls.OUTGOING_TYPE -> {
                    dao.upsertOutboundGrace(
                        OutboundGraceEntity(
                            normalizedNumber = normalized.normalizedNumber,
                            prefix6 = normalized.prefix6,
                            expiresAt = now + SpamScoringEngine.OUTBOUND_GRACE_WINDOW_MS
                        )
                    )
                    Log.d(TAG, "Registered Outbound Grace (2h) for ${normalized.normalizedNumber} (prefix6=${normalized.prefix6})")
                }
                CallLog.Calls.INCOMING_TYPE -> {
                    if (durationSec in 1..4) {
                        val flagged = spamScoringEngine.evaluatePostCallPingDetector(
                            normalizedNumber = normalized.normalizedNumber,
                            callDurationSec = durationSec,
                            nowMillis = now
                        )
                        if (flagged) {
                            Log.d(
                                TAG,
                                "Ping Call detected (${durationSec}s) from ${normalized.normalizedNumber}! " +
                                    "Prefix ${normalized.prefix7} quarantined (+65)."
                            )
                            SyncWorker.enqueueOneTimeSync(context)
                        }
                    }
                }
            }
        }
    }
}
