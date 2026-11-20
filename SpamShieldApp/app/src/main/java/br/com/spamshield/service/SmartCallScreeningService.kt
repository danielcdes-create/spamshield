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
import br.com.spamshield.domain.FeatureRepository
import br.com.spamshield.domain.ExpectedWindow
import java.util.concurrent.atomic.AtomicReference
import br.com.spamshield.domain.ScreeningPolicyDecision
import br.com.spamshield.domain.ScreeningLatency
import br.com.spamshield.domain.ScreeningPolicy
import br.com.spamshield.domain.ScreeningTiming
import br.com.spamshield.domain.UserFeedbackAudit
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class SmartCallScreeningService : CallScreeningService() {

    @Inject
    lateinit var spamScoringEngine: SpamScoringEngine

    @Inject lateinit var dao: SpamShieldDao
    @Inject lateinit var callHistoryAnalyzer: CallHistoryAnalyzer
    @Inject lateinit var features: FeatureRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val responseHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "SmartCallScreening"
        private const val SHADOW_CHANNEL_ID = "spamshield_shadow_audit"
        private const val WAIT_RISK_CHANNEL_ID = "spamshield_wait_risk"
        private const val SCREENING_SOURCE = "CALL_SCREENING_SERVICE"
        private const val TIMEOUT_FALLBACK = "FALLBACK_AFTER_TIMEOUT"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val started = SystemClock.elapsedRealtimeNanos()
        val callbackAt = System.currentTimeMillis()
        val timing = ScreeningTiming(started, SystemClock::elapsedRealtimeNanos)
        val capturedWindow = AtomicReference<ExpectedWindow?>(null)
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

        fun submit(
            prepared: PreparedScreening?, fallbackReason: String? = null, errorType: String? = null,
            policy: ScreeningPolicyDecision? = null
        ): Boolean {
            val claim = deadline.claim() ?: return false
            responseHandler.removeCallbacks(timeout)
            val evaluated = prepared.takeUnless { claim.timedOut }
            val result = evaluated?.result
            val reason = if (claim.timedOut) TIMEOUT_FALLBACK else fallbackReason
            val applied = policy.takeIf { evaluated != null }
                ?: ScreeningPolicyDecision("UNKNOWN", "ALLOW", reason ?: TIMEOUT_FALLBACK,
                    windowId = capturedWindow.get()?.takeIf { it.activeAt(callbackAt) }?.id)
            val disallow = applied.chosenAction == "BLOCK"
            val silence = applied.chosenAction == "SILENCE"
            val skipNotification = disallow && result?.shouldSkipNotification == true
            val skipCallLog = disallow && result?.shouldSkipCallLog == true
            val response = CallResponse.Builder()
                .setDisallowCall(disallow)
                .setRejectCall(disallow)
                .setSilenceCall(silence)
                .setSkipNotification(skipNotification)
                .setSkipCallLog(skipCallLog)
                .build()
            val normalized = result?.telemetry?.normalizedNumber ?: PhoneNormalizer.normalize(rawNumber).normalizedNumber
            val pending = spamScoringEngine.beginScreeningAudit(normalized, callAt)
            timing.mark("SUBMITTING")
            val submissionFailure = runCatching { respondToCall(callDetails, response) }.exceptionOrNull()
            timing.mark("SUBMITTED")
            if (submissionFailure != null) Log.e(TAG, "Could not submit screening response", submissionFailure)
            val submittedAt = System.currentTimeMillis()
            val details = DecisionDetails(
                disallowCall = disallow,
                rejectCall = disallow,
                skipNotification = skipNotification,
                skipCallLog = skipCallLog,
                elapsedMs = timing.elapsed(), silenceCall = silence, callAt = callAt,
                evidenceAt = submittedAt, reason = reason, policy = applied,
                latency = timing.snapshot(ScreeningDeadline.BUDGET_MS, reason == TIMEOUT_FALLBACK)
            )

            if (result != null && submissionFailure == null && (result.shouldNotifyShadowBlock ||
                    applied.reason == "EXPECTED_CALL_WINDOW")) {
                serviceScope.launch {
                    runCatching { notifyCallRisk(result.telemetry.id, result.telemetry.finalScore, applied.reason == "EXPECTED_CALL_WINDOW") }
                        .onFailure { Log.w(TAG, "Could not notify call risk", it) }
                }
            }

            // The Telecom response is sent even if database or provider work is unresponsive.
            serviceScope.launch {
                try {
                    val experiment = try {
                        features.shadowAt(callbackAt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not read shadow experiment", error)
                        null
                    }
                    val journalStart = timing.elapsed()
                    val persisted = if (evaluated != null && result != null) {
                        val decorated = evaluated.copy(decisionDetails = {
                            evaluated.decisionDetails().copy(callAt = callAt, evidenceAt = callbackAt,
                                policy = applied, experimentId = experiment?.id)
                        })
                        spamScoringEngine.recordIncomingCall(decorated, submittedAt, details,
                            submissionFailure?.javaClass?.simpleName).telemetry.id
                    } else {
                        recordFallback(rawNumber, stirShakenStatus, callAt, submittedAt, details.copy(experimentId = experiment?.id),
                            reason ?: TIMEOUT_FALLBACK, errorType, submissionFailure?.javaClass?.simpleName)
                    }
                    val journalMs = timing.elapsed() - journalStart
                    val candidateStart = timing.elapsed()
                    val candidateError = if (experiment != null && evaluated != null) {
                        try {
                            features.compareShadow(experiment, persisted, System.currentTimeMillis())
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            error.javaClass.simpleName
                        }
                    } else null
                    val candidateMs = if (experiment != null && evaluated != null) timing.elapsed() - candidateStart else null
                    DecisionTrace.record(dao, persisted, "SCREENING_POST_RESPONSE", SCREENING_SOURCE,
                        DecisionDetails(callAt = callAt, experimentId = experiment?.id, reason = candidateError,
                            latency = ScreeningLatency(journalMs = journalMs, candidateMs = candidateMs)))
                    features.reconcile()
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
                timing.mark("EVALUATION")
                val silences = try {
                    withTimeoutOrNull(50) { features.activeSilences(callbackAt) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not read campaign silences", error)
                    null
                }
                val prepared = spamScoringEngine.prepareIncomingCall(rawNumber, stirShakenStatus, callAt, timing, capturedWindow::set)
                val row = prepared.result.telemetry
                val prefix = row.prefix7
                val exact = silences?.lastOrNull { it.prefix == prefix && it.startedAt <= callAt }
                val neighbor = silences?.lastOrNull { prefix.length == 8 && it.prefix != prefix &&
                    it.prefix.take(7) == prefix.take(7) && it.startedAt <= callAt }
                val policy = ScreeningPolicy.resolve(row.outcome, UserFeedbackAudit.decode(row.triggeredRulesJson),
                    prepared.quarantineMatch, prepared.window, callbackAt, exact, neighbor, row.shadowModeActive)
                    .copy(campaignContextReady = silences != null)
                timing.mark("POLICY_READY")
                submit(prepared, policy = policy)
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
        timing.mark("QUEUED")
        responseHandler.postDelayed(timeout, (ScreeningDeadline.BUDGET_MS - timing.elapsed()).toLong().coerceAtLeast(0))
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
    ): String {
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
        val recordedAt = System.currentTimeMillis()
        return dao.persistScreeningAudit(row, listOf(
            DecisionTrace.buildEvent(row.id, failureType, SCREENING_SOURCE, DecisionDetails(callAt = callAt, evidenceAt = submittedAt, reason = errorType ?: reason), recordedAt, key = "arrival-fallback"),
            DecisionTrace.buildEvent(row.id, eventType, SCREENING_SOURCE, response.copy(reason = submissionFailure ?: reason), recordedAt, key = "submission")
        )).id
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

    private fun notifyCallRisk(callId: String, score: Int, expectedWindow: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (expectedWindow) WAIT_RISK_CHANNEL_ID else SHADOW_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            if (expectedWindow) "Risco na janela de espera" else "Auditoria Modo Sombra",
            if (expectedWindow) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
        ).apply {
            if (expectedWindow) setSound(null, null)
        }
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (expectedWindow) "Ligação liberada na janela de espera" else "Modo Sombra — Spam Detectado")
            .setContentText("Risco calculado na chegada. Pontuação $score.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(callId.hashCode(), notification)
    }
}
