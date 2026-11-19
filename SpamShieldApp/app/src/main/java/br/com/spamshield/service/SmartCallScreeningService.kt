package br.com.spamshield.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import br.com.spamshield.domain.PreparedScreening
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmartCallScreeningService : CallScreeningService() {

    @Inject
    lateinit var spamScoringEngine: SpamScoringEngine

    @Inject lateinit var dao: SpamShieldDao
    @Inject lateinit var callHistoryAnalyzer: CallHistoryAnalyzer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val responseHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "SmartCallScreening"
        private const val SHADOW_CHANNEL_ID = "spamshield_shadow_audit"
        private const val SCREENING_SOURCE = "CALL_SCREENING_SERVICE"
        private const val TIMEOUT_FALLBACK = "FALLBACK_AFTER_TIMEOUT"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val started = SystemClock.elapsedRealtimeNanos()
        // Only screen incoming calls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val stirShakenStatus = mapVerificationStatus(callDetails)
        val callAt = callDetails.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val deadline = ScreeningDeadline(started, SystemClock::elapsedRealtimeNanos)
        lateinit var timeout: Runnable

        fun submit(prepared: PreparedScreening?, fallbackReason: String? = null, errorType: String? = null): Boolean {
            val claim = deadline.claim() ?: return false
            responseHandler.removeCallbacks(timeout)
            val evaluated = prepared.takeUnless { claim.timedOut }
            val result = evaluated?.result
            val reason = if (claim.timedOut) TIMEOUT_FALLBACK else fallbackReason
            val disallow = result?.shouldDisallowAndReject == true
            val skipNotification = result?.shouldSkipNotification == true
            val skipCallLog = result?.shouldSkipCallLog == true
            val response = CallResponse.Builder()
                .setDisallowCall(disallow)
                .setRejectCall(disallow)
                .setSkipNotification(skipNotification)
                .setSkipCallLog(skipCallLog)
                .build()
            val normalized = result?.telemetry?.normalizedNumber ?: PhoneNormalizer.normalize(rawNumber).normalizedNumber
            val pending = spamScoringEngine.beginScreeningAudit(normalized, callAt)
            val submissionFailure = runCatching { respondToCall(callDetails, response) }.exceptionOrNull()
            if (submissionFailure != null) Log.e(TAG, "Could not submit screening response", submissionFailure)
            val submittedAt = System.currentTimeMillis()
            val details = DecisionDetails(
                disallowCall = disallow,
                rejectCall = disallow,
                skipNotification = skipNotification,
                skipCallLog = skipCallLog,
                elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            )

            // The Telecom response is sent even if database or provider work is unresponsive.
            serviceScope.launch {
                try {
                    if (evaluated != null && result != null) {
                        spamScoringEngine.recordIncomingCall(evaluated, submittedAt, details, submissionFailure?.javaClass?.simpleName)
                        if (submissionFailure == null && result.shouldNotifyShadowBlock) {
                            sendShadowModeNotification(
                                result.telemetry.rawNumber.ifBlank { "Número Oculto" },
                                result.telemetry.finalScore
                            )
                        }
                    } else {
                        recordFallback(rawNumber, stirShakenStatus, callAt, submittedAt, details,
                            reason ?: TIMEOUT_FALLBACK, errorType, submissionFailure?.javaClass?.simpleName)
                    }
                    SyncWorker.enqueueOneTimeSync(applicationContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Could not persist screening result", error)
                } finally {
                    if (spamScoringEngine.endScreeningAudit(pending)) {
                        serviceScope.launch {
                            runCatching { callHistoryAnalyzer.reconcileRecentCallsWithCallLog(100) }
                                .onFailure { Log.e(TAG, "Could not reconcile deferred CallLog", it) }
                        }
                    }
                }
            }
            return true
        }

        val evaluation = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                submit(spamScoringEngine.prepareIncomingCall(rawNumber, stirShakenStatus, callAt))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (submit(null, "FALLBACK_AFTER_ERROR", error.javaClass.simpleName)) {
                    Log.e(TAG, "Screening evaluation failed", error)
                }
            }
        }
        timeout = Runnable {
            if (submit(null, TIMEOUT_FALLBACK)) evaluation.cancel()
        }
        responseHandler.postDelayed(timeout, ScreeningDeadline.BUDGET_MS)
        evaluation.start()
    }

    private suspend fun recordFallback(
        rawNumber: String,
        stirShakenStatus: String,
        callAt: Long,
        submittedAt: Long,
        response: DecisionDetails,
        reason: String,
        errorType: String?,
        submissionFailure: String?
    ) {
        val normalized = PhoneNormalizer.normalize(rawNumber)
        val row = CallTelemetryEntity(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = callAt,
            deviceId = "android-edge-br",
            rawNumber = rawNumber,
            normalizedNumber = normalized.normalizedNumber,
            ddd = normalized.ddd,
            prefix7 = normalized.storedPrefix7.orEmpty(),
            carrierGuess = normalized.carrierHint,
            lineType = normalized.lineType,
            stirShakenStatus = stirShakenStatus,
            finalScore = 0,
            thresholdApplied = 0,
            outcome = "ALLOWED_LOW_SCORE",
            category = "UNKNOWN_UNVERIFIED",
            processingLatencyMs = response.elapsedMs ?: 0.0,
            shadowModeActive = false,
            triggeredRulesJson = "[]"
        )
        val failureType = if (reason == TIMEOUT_FALLBACK) "SCREENING_TIMEOUT" else "SCREENING_ERROR"
        val eventType = if (submissionFailure == null) "SCREENING_RESPONSE_SUBMITTED" else "SCREENING_RESPONSE_FAILED"
        dao.persistScreeningAudit(row, listOf(
            DecisionTrace.buildEvent(row.id, failureType, SCREENING_SOURCE, DecisionDetails(reason = errorType ?: reason), submittedAt),
            DecisionTrace.buildEvent(row.id, eventType, SCREENING_SOURCE, response.copy(reason = submissionFailure ?: reason), submittedAt)
        ))
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
