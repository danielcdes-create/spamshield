package br.com.spamshield.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmartCallScreeningService : CallScreeningService() {

    @Inject
    lateinit var spamScoringEngine: SpamScoringEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SmartCallScreening"
        private const val SHADOW_CHANNEL_ID = "spamshield_shadow_audit"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        // Only screen incoming calls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val stirShakenStatus = mapVerificationStatus(callDetails)

        serviceScope.launch {
            try {
                val evalResult = spamScoringEngine.evaluateIncomingCall(
                    rawNumber = rawNumber,
                    stirShakenStatus = stirShakenStatus
                )

                val responseBuilder = CallResponse.Builder()
                    .setDisallowCall(evalResult.shouldDisallowAndReject)
                    .setRejectCall(evalResult.shouldDisallowAndReject)
                    .setSkipNotification(evalResult.shouldSkipNotification)
                    .setSkipCallLog(evalResult.shouldSkipCallLog)

                respondToCall(callDetails, responseBuilder.build())

                Log.d(
                    TAG,
                    "Screened call from $rawNumber -> outcome=${evalResult.telemetry.outcome}, " +
                        "score=${evalResult.telemetry.finalScore}, " +
                        "latency=${String.format("%.2f", evalResult.telemetry.processingLatencyMs)}ms"
                )

                if (evalResult.shouldNotifyShadowBlock) {
                    sendShadowModeNotification(
                        rawNumber = evalResult.telemetry.rawNumber.ifBlank { "Número Oculto" },
                        score = evalResult.telemetry.finalScore
                    )
                }

                // Trigger opportunist background sync via Tailscale if available
                SyncWorker.enqueueOneTimeSync(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error screening call from $rawNumber, failing open", e)
                respondToCall(callDetails, CallResponse.Builder().build())
            }
        }
    }

    private fun mapVerificationStatus(callDetails: Call.Details): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (callDetails.callerNumberVerificationStatus) {
                Connection.VERIFICATION_STATUS_PASSED -> "PASSED"
                Connection.VERIFICATION_STATUS_FAILED -> "FAILED"
                else -> "NOT_VERIFIED"
            }
        } else {
            "NOT_VERIFIED"
        }
    }

    private fun sendShadowModeNotification(rawNumber: String, score: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            SHADOW_CHANNEL_ID,
            "Auditoria Modo Sombra",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas de chamadas que teriam sido bloqueadas no Modo Sombra"
        }
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, SHADOW_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Modo Sombra — Spam Detectado")
            .setContentText("Modo Sombra: Chamada de $rawNumber teria sido bloqueada (Score: $score)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Modo Sombra: Chamada de $rawNumber teria sido bloqueada (Score: $score)"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(rawNumber.hashCode(), notification)
    }
}
