package br.com.spamshield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.spamshield.BuildConfig
import br.com.spamshield.data.local.SettingsDataStore
import br.com.spamshield.data.local.SpamShieldSettings
import br.com.spamshield.data.local.dao.SpamShieldDao
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.domain.SpamScoringEngine
import br.com.spamshield.service.CallHistoryAnalyzer
import br.com.spamshield.sync.SyncRepository
import br.com.spamshield.sync.TailscaleNetworkHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

data class TailscaleStatusUi(
    val isVpnActive: Boolean = false,
    val detectedIp: String = "100.83.253.15",
    val isServerOnline: Boolean = false,
    val isSyncing: Boolean = false,
    val isImportingHistory: Boolean = false,
    val lastSyncMessage: String = "Pronto para sincronizar"
)

data class ApkUpdateUiState(
    val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    val installedVersionName: String = BuildConfig.VERSION_NAME,
    val remoteVersionCode: Int? = null,
    val remoteVersionName: String? = null,
    val remoteSizeBytes: Long = 0L,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val statusMessage: String = "Toque em Verificar para checar nova versão no servidor Tailscale."
) {
    val hasNewerVersion: Boolean
        get() = remoteVersionCode != null && remoteVersionCode > installedVersionCode
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dao: SpamShieldDao,
    private val settingsDataStore: SettingsDataStore,
    private val syncRepository: SyncRepository,
    private val spamScoringEngine: SpamScoringEngine,
    private val callHistoryAnalyzer: CallHistoryAnalyzer
) : ViewModel() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val telemetryList: StateFlow<List<CallTelemetryEntity>> = dao
        .observeAllTelemetry()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRules: StateFlow<List<ActiveRuleEntity>> = dao
        .observeAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeQuarantines: StateFlow<List<PrefixQuarantineEntity>> = dao
        .observeActiveQuarantines(0L)
        .map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.expiresAt > now }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<SpamShieldSettings> = settingsDataStore
        .settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpamShieldSettings())

    private val _tailscaleStatus = MutableStateFlow(TailscaleStatusUi())
    val tailscaleStatus: StateFlow<TailscaleStatusUi> = _tailscaleStatus.asStateFlow()

    private val _apkUpdateState = MutableStateFlow(ApkUpdateUiState())
    val apkUpdateState: StateFlow<ApkUpdateUiState> = _apkUpdateState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dao.deduplicateHistoryRecords()
                val reconciled = callHistoryAnalyzer.reconcileRecentCallsWithCallLog(100)
                val updated = callHistoryAnalyzer.reprocessAllStoredTelemetry()
                if (updated > 0 || reconciled > 0) {
                    syncRepository.performFullSync()
                }
            }
        }
        refreshTailscaleStatus()
    }

    fun refreshTailscaleStatus() {
        viewModelScope.launch {
            val vpn = TailscaleNetworkHelper.isVpnActive(appContext)
            val ip = TailscaleNetworkHelper.detectLocalTailscaleIp() ?: "100.83.253.15"
            val currentSettings = settingsDataStore.getSnapshot()
            val reachable = syncRepository.checkServerReachable(currentSettings.tailscaleServerUrl)

            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isVpnActive = vpn,
                detectedIp = ip,
                isServerOnline = reachable
            )
        }
    }

    fun syncNowWithMongoDb() {
        viewModelScope.launch {
            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isSyncing = true,
                lastSyncMessage = "Sincronizando via Tailscale..."
            )
            withContext(Dispatchers.IO) {
                runCatching {
                    callHistoryAnalyzer.reconcileRecentCallsWithCallLog(100)
                    callHistoryAnalyzer.reprocessAllStoredTelemetry()
                }
            }
            val outcome = syncRepository.performFullSync()
            val vpn = TailscaleNetworkHelper.isVpnActive(appContext)
            val ip = TailscaleNetworkHelper.detectLocalTailscaleIp() ?: "100.83.253.15"

            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isVpnActive = vpn,
                detectedIp = ip,
                isServerOnline = outcome.success,
                isSyncing = false,
                lastSyncMessage = outcome.message
            )
        }
    }

    fun importAndAnalyzePhoneCallHistory() {
        viewModelScope.launch {
            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isImportingHistory = true,
                lastSyncMessage = "Lendo e analisando histórico de chamadas do aparelho..."
            )
            val res = callHistoryAnalyzer.importAndAnalyzeSystemCallLog(maxRecords = 300)
            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isImportingHistory = false,
                lastSyncMessage = res.message
            )
        }
    }

    fun syncAnatelAndBlacklistProviders() {
        viewModelScope.launch {
            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isSyncing = true,
                lastSyncMessage = "Atualizando base Anatel e Blacklists BR..."
            )
            val outcome = syncRepository.triggerProvidersSync()
            _tailscaleStatus.value = _tailscaleStatus.value.copy(
                isSyncing = false,
                isServerOnline = outcome.success,
                lastSyncMessage = outcome.message
            )
        }
    }

    fun checkApkUpdate() {
        viewModelScope.launch {
            _apkUpdateState.value = _apkUpdateState.value.copy(
                isChecking = true,
                statusMessage = "Consultando servidor Tailscale..."
            )
            val info = syncRepository.fetchRemoteApkInfo()
            if (info == null) {
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    isChecking = false,
                    statusMessage = "Não foi possível consultar o servidor Tailscale."
                )
            } else {
                val installedCode = BuildConfig.VERSION_CODE
                val sizeMb = String.format("%.1f MB", info.size_bytes / (1024.0 * 1024.0))
                val msg = if (info.version_code > installedCode) {
                    "Nova versão disponível no servidor: v${info.version_name} (build ${info.version_code}, $sizeMb)!"
                } else {
                    "Você já está na versão mais recente (v${info.version_name}, build ${info.version_code}, $sizeMb). Pode reinstalar se desejar."
                }
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    isChecking = false,
                    remoteVersionCode = info.version_code,
                    remoteVersionName = info.version_name,
                    remoteSizeBytes = info.size_bytes,
                    statusMessage = msg
                )
            }
        }
    }

    fun downloadAndInstallApk(context: Context) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                runCatching {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permIntent)
                }
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    statusMessage = "Autorize 'Instalar apps desconhecidos' para o SpamShield BR e toque novamente em Atualizar."
                )
                return@launch
            }

            _apkUpdateState.value = _apkUpdateState.value.copy(
                isDownloading = true,
                statusMessage = "Baixando APK via Tailscale..."
            )
            val updatesDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
            val apkFile = syncRepository.downloadApkUpdate(updatesDir)
            if (apkFile == null || !apkFile.exists()) {
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    isDownloading = false,
                    statusMessage = "Falha ao baixar o APK do servidor Tailscale."
                )
                return@launch
            }

            _apkUpdateState.value = _apkUpdateState.value.copy(
                isDownloading = false,
                statusMessage = "APK baixado (${apkFile.length() / 1024} KB). Abrindo instalador do Android..."
            )

            runCatching {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }.onFailure { err ->
                _apkUpdateState.value = _apkUpdateState.value.copy(
                    statusMessage = "Erro ao abrir instalador: ${err.localizedMessage}"
                )
            }
        }
    }

    fun setShadowMode(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setShadowMode(enabled) }
    }

    fun setThresholdScore(score: Int) {
        viewModelScope.launch { settingsDataStore.setBlockThreshold(score) }
    }

    fun setTailscaleUrl(url: String) {
        viewModelScope.launch {
            settingsDataStore.setTailscaleServerUrl(url)
            refreshTailscaleStatus()
        }
    }

    fun setHideBlockedFromCallLog(hide: Boolean) {
        viewModelScope.launch { settingsDataStore.setHideBlockedFromSystemCallLog(hide) }
    }

    fun markCallFeedback(telemetry: CallTelemetryEntity, feedback: String) {
        viewModelScope.launch {
            val targetPrefix = spamScoringEngine.extractQuarantinePrefix(
                telemetry.normalizedNumber,
                telemetry.prefix7
            )
            syncRepository.submitFeedback(telemetry.id, feedback, targetPrefix)
        }
    }

    fun addCustomRegexOrPrefixRule(
        ruleId: String,
        name: String,
        pattern: String,
        weight: Int
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val cleanId = ruleId.trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_")
                .ifEmpty { "CUSTOM_RULE_${now % 10000}" }
            val entity = ActiveRuleEntity(
                ruleId = cleanId,
                name = name.ifBlank { "Regra Customizada ($cleanId)" },
                ruleType = "REGEX_MATCH",
                pattern = pattern.trim(),
                weight = weight.coerceIn(-500, 200),
                priority = 25,
                enabled = true,
                updatedAt = now
            )
            syncRepository.saveCustomRule(entity)
        }
    }

    fun addManualQuarantinePrefix(prefixInput: String, carrierGuess: String, weight: Int) {
        viewModelScope.launch {
            val cleanPrefix = prefixInput.filter { it.isDigit() }.take(8)
            if (cleanPrefix.length < 5) return@launch
            val now = System.currentTimeMillis()
            val isFixed = cleanPrefix.drop(2).firstOrNull() in '2'..'5'
            val normalizedCarrier = br.com.spamshield.domain.PhoneNormalizer.normalizeCarrierName(
                rawCarrier = carrierGuess.ifBlank { null },
                lineType = if (isFixed) "FIXED_OR_VOIP" else "MOBILE",
                isCorporatePabx = false,
                prefix7 = cleanPrefix.take(7)
            )
            syncRepository.saveManualQuarantine(
                PrefixQuarantineEntity(
                    prefix7 = cleanPrefix,
                    ddd = cleanPrefix.take(2),
                    carrierGuess = normalizedCarrier,
                    distinctNumbersJson = "[]",
                    hitCount = 1,
                    penaltyWeight = weight.coerceIn(10, 100),
                    reason = "ROBOCALL_PREFIX_CLUSTER",
                    firstSeenAt = now,
                    lastSeenAt = now,
                    expiresAt = now + 7L * 24 * 60 * 60 * 1000L
                )
            )
        }
    }

    fun removeQuarantinePrefix(prefix7: String) {
        viewModelScope.launch {
            syncRepository.deleteManualQuarantine(prefix7)
        }
    }

    fun toggleRuleEnabled(rule: ActiveRuleEntity) {
        viewModelScope.launch {
            syncRepository.saveCustomRule(rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis()))
        }
    }

    fun simulateTestCall(rawNumber: String, stirShaken: String) {
        viewModelScope.launch {
            spamScoringEngine.evaluateIncomingCall(
                rawNumber = rawNumber,
                stirShakenStatus = stirShaken
            )
        }
    }

    fun parseTriggeredRules(jsonStr: String): List<TriggeredRuleDto> {
        return runCatching {
            json.decodeFromString<List<TriggeredRuleDto>>(jsonStr)
        }.getOrDefault(emptyList())
    }

    suspend fun exportTelemetryFiles(): Pair<File, File> = withContext(Dispatchers.IO) {
        runCatching {
            callHistoryAnalyzer.reconcileRecentCallsWithCallLog(100)
            callHistoryAnalyzer.reprocessAllStoredTelemetry()
        }
        val all = dao.getAllTelemetry()
        val exportDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "exports")
        exportDir.mkdirs()

        val jsonFile = File(exportDir, "spamshield_telemetry.json")
        jsonFile.writeText(json.encodeToString(all))

        val csvFile = File(exportDir, "spamshield_telemetry.csv")
        val sb = StringBuilder()
        sb.appendLine("id,timestamp,rawNumber,normalizedNumber,ddd,prefix7,carrierGuess,lineType,stirShakenStatus,finalScore,thresholdApplied,outcome,category,processingLatencyMs,userFeedback,syncStatus")
        for (row in all) {
            sb.appendLine(
                "${row.id},${row.timestamp},${row.rawNumber},${row.normalizedNumber},${row.ddd},${row.prefix7}," +
                    "\"${row.carrierGuess ?: ""}\",${row.lineType},${row.stirShakenStatus},${row.finalScore}," +
                    "${row.thresholdApplied},${row.outcome},${row.category},${row.processingLatencyMs},${row.userFeedback},${row.syncStatus}"
            )
        }
        csvFile.writeText(sb.toString())

        Pair(jsonFile, csvFile)
    }
}
