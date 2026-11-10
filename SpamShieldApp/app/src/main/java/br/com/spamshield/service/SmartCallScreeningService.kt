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
import br.com.spamshield.domain.DecisionTrace
import br.com.spamshield.domain.DecisionDetails
import br.com.spamshield.domain.PhoneNormalizer
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallTelemetryEntity
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

    @Inject lateinit var dao: SpamShieldDao

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
        val started = android.os.SystemClock.elapsedRealtimeNanos()

        serviceScope.launch {
            var responseSubmitted = false
            var evaluated: CallTelemetryEntity? = null
            try {
                val evalResult = spamScoringEngine.evaluateIncomingCall(
                    rawNumber = rawNumber,
                    stirShakenStatus = stirShakenStatus,
                    nowMillis = callDetails.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    decisionSource = "CALL_SCREENING_SERVICE"
                )

                evaluated = evalResult.telemetry
                val responseBuilder = CallResponse.Builder()
                    .setDisallowCall(evalResult.shouldDisallowAndReject)
                    .setRejectCall(evalResult.shouldDisallowAndReject)
                    .setSkipNotification(evalResult.shouldSkipNotification)
                    .setSkipCallLog(evalResult.shouldSkipCallLog)

                respondToCall(callDetails, responseBuilder.build())
                responseSubmitted = true
                // Returning from respondToCall is submission, not proof that the phone never rang.
                runCatching {
                    DecisionTrace.record(dao, evalResult.telemetry.id, "SCREENING_RESPONSE_SUBMITTED", "CALL_SCREENING_SERVICE",
                        DecisionDetails(disallowCall = evalResult.shouldDisallowAndReject,
                            rejectCall = evalResult.shouldDisallowAndReject,
                            skipNotification = evalResult.shouldSkipNotification,
                            skipCallLog = evalResult.shouldSkipCallLog,
                            elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0))
                }.onFailure { Log.e(TAG, "Screening response submitted but journal persistence failed", it) }

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
                Log.e(TAG, "Screening error; responseSubmitted=$responseSubmitted", e)
                if (!responseSubmitted) {
                    val failedAt = System.currentTimeMillis()
                    val submitted = runCatching { respondToCall(callDetails, CallResponse.Builder().build()) }.isSuccess
                    runCatching {
                        val n = PhoneNormalizer.normalize(rawNumber)
                        val row = evaluated ?: CallTelemetryEntity(
                            id = java.util.UUID.randomUUID().toString(), timestamp = callDetails.creationTimeMillis.takeIf { it > 0 } ?: failedAt,
                            deviceId = "android-edge-br", rawNumber = rawNumber, normalizedNumber = n.normalizedNumber,
                            ddd = n.ddd, prefix7 = n.storedPrefix7.orEmpty(), carrierGuess = n.carrierHint,
                            lineType = n.lineType, stirShakenStatus = stirShakenStatus, finalScore = 0,
                            thresholdApplied = 0, outcome = "ALLOWED_LOW_SCORE", category = "UNKNOWN_UNVERIFIED",
                            processingLatencyMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                            shadowModeActive = false, triggeredRulesJson = "[]")
                        if (evaluated == null) dao.insertTelemetry(row)
                        DecisionTrace.record(dao, row.id,
                            if (evaluated == null) "SCREENING_ERROR" else "SCREENING_RESPONSE_FAILED", "CALL_SCREENING_SERVICE",
                            DecisionDetails(rawNumber = rawNumber, reason = e.javaClass.simpleName), at = failedAt)
                        DecisionTrace.record(dao, row.id,
                            if (submitted) "SCREENING_RESPONSE_SUBMITTED" else "SCREENING_RESPONSE_FAILED",
                            "CALL_SCREENING_SERVICE", DecisionDetails(disallowCall = false, rejectCall = false,
                                reason = "FALLBACK_AFTER_ERROR", elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0))
                        SyncWorker.enqueueOneTimeSync(applicationContext)
                    }.onFailure { Log.e(TAG, "Could not persist screening failure", it) }
                }
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
