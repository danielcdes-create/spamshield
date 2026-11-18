package br.com.spamshield

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.spamshield.service.RoleManagerHelper
import br.com.spamshield.ui.ApkInstallEvent
import br.com.spamshield.ui.MainViewModel
import br.com.spamshield.ui.screens.CallAuditScreen
import br.com.spamshield.ui.screens.DashboardScreen
import br.com.spamshield.ui.screens.QuarantineRulesScreen
import br.com.spamshield.ui.screens.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

private val SpamShieldDarkScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    secondary = Color(0xFF22C55E),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFF334155)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = SpamShieldDarkScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpamShieldMainApp()
                }
            }
        }
    }
}

@Composable
fun SpamShieldMainApp(viewModel: MainViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    fun missingDataPermissions(): Boolean = listOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE
    ).any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    var hasMissingDataPermissions by remember { mutableStateOf(missingDataPermissions()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isScreeningRoleHeld by remember {
        mutableStateOf(RoleManagerHelper.isCallScreeningRoleHeld(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreeningRoleHeld = RoleManagerHelper.isCallScreeningRoleHeld(context)
                viewModel.refreshTailscaleStatus()
                hasMissingDataPermissions = missingDataPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val held = RoleManagerHelper.isCallScreeningRoleHeld(context)
        isScreeningRoleHeld = held
        if (!held) {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMissingDataPermissions = missingDataPermissions()
    }

    val requestDataPermissions = {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    if (!settings.localDataDisclosureAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Como o SpamShield usa seus dados") },
            text = { Text("O app consulta contatos para permitir pessoas conhecidas e o histórico de ligações para analisar o resultado das chamadas. Esses dados são tratados no aparelho. A importação do histórico antigo é uma ação separada em Ajustes. Números, horários e decisões só são enviados ao servidor configurado se você ativar a sincronização em Ajustes. O app não solicita acesso a SMS, fotos ou microfone. Você pode rever os acessos nas configurações do Android.") },
            confirmButton = { TextButton(onClick = {
                viewModel.acceptLocalDataDisclosure()
                requestDataPermissions()
            }) { Text("Continuar e escolher acessos") } },
            dismissButton = { TextButton(onClick = { viewModel.acceptLocalDataDisclosure() }) {
                Text("Continuar sem novos acessos")
            } }
        )
    }

    val telemetryList by viewModel.telemetryList.collectAsStateWithLifecycle()
    val decisionEvents by viewModel.decisionEvents.collectAsStateWithLifecycle()
    val activeRules by viewModel.activeRules.collectAsStateWithLifecycle()
    val activeQuarantines by viewModel.activeQuarantines.collectAsStateWithLifecycle()
    val tailscaleStatus by viewModel.tailscaleStatus.collectAsStateWithLifecycle()
    val apkUpdateState by viewModel.apkUpdateState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.apkInstallEvents.collect { event ->
            when (event) {
                ApkInstallEvent.NeedsUnknownSourcesPermission -> {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                is ApkInstallEvent.ReadyToInstall -> {
                    runCatching {
                        val apkUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            event.apkFile
                        )
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(apkUri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
            if (hasMissingDataPermissions && settings.localDataDisclosureAccepted) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Acesso a contatos ou ligações ausente", fontWeight = FontWeight.Bold)
                        Text("O histórico e a proteção de contatos ficam limitados. Se o Play Protect removeu acessos, conclua a revisão do alerta antes de concedê-los novamente.", fontSize = 12.sp)
                        TextButton(onClick = requestDataPermissions) { Text("Revisar permissões") }
                    }
                }
            }
            if (!isScreeningRoleHeld) {
                Surface(
                    color = Color(0xFFB91C1C),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Permissão de Bloqueador Padrão Pendente",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Escolha o SpamShield como identificador e bloqueador de chamadas.",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = {
                                RoleManagerHelper.createCallScreeningRoleIntent(context)?.let {
                                    roleLauncher.launch(it)
                                }
                            }
                        ) {
                            Text("Ativar", fontSize = 12.sp)
                        }
                    }
                }
            }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Auditoria") },
                    label = { Text("Auditoria") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.GppBad, contentDescription = "Quarentenas") },
                    label = { Text("Quarentenas") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                    label = { Text("Tailscale") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    telemetryList = telemetryList,
                    decisionEvents = decisionEvents,
                    settings = settings,
                    tailscaleStatus = tailscaleStatus,
                    onSyncNow = viewModel::syncNowWithMongoDb
                )
                1 -> CallAuditScreen(
                    telemetryList = telemetryList,
                    decisionEvents = decisionEvents,
                    parseTriggeredRules = viewModel::parseTriggeredRules,
                    onMarkFeedback = viewModel::markCallFeedback
                )
                2 -> QuarantineRulesScreen(
                    quarantines = activeQuarantines,
                    rules = activeRules,
                    onAddCustomRule = viewModel::addCustomRegexOrPrefixRule,
                    onAddManualQuarantine = viewModel::addManualQuarantinePrefix,
                    onRemoveQuarantine = viewModel::removeQuarantinePrefix,
                    onToggleRule = viewModel::toggleRuleEnabled
                )
                3 -> SettingsScreen(
                    settings = settings,
                    tailscaleStatus = tailscaleStatus,
                    apkUpdateState = apkUpdateState,
                    onToggleShadowMode = viewModel::setShadowMode,
                    onToggleRemoteSync = viewModel::setRemoteSyncEnabled,
                    onSyncNow = viewModel::syncNowWithMongoDb,
                    onImportCallHistory = viewModel::importAndAnalyzePhoneCallHistory,
                    onSyncAnatelProviders = viewModel::syncAnatelAndBlacklistProviders,
                    onSimulateAttack = viewModel::simulateTestCall,
                    onSaveTailscaleUrl = viewModel::setTailscaleUrl,
                    onSaveApiBearerToken = viewModel::setApiBearerToken,
                    onChangeThreshold = viewModel::setThresholdScore,
                    onToggleSkipCallLog = viewModel::setHideBlockedFromCallLog,
                    onCheckApkUpdate = viewModel::checkApkUpdate,
                    onDownloadAndInstallApk = viewModel::downloadAndInstallApk,
                    onExportFiles = viewModel::exportTelemetryFiles
                )
            }
        }
    }
}
