package br.com.spamshield

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
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
    var selectedTab by remember { mutableIntStateOf(0) }
    var isScreeningRoleHeld by remember {
        mutableStateOf(RoleManagerHelper.isCallScreeningRoleHeld(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreeningRoleHeld = RoleManagerHelper.isCallScreeningRoleHeld(context)
                viewModel.refreshTailscaleStatus()
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
    ) { grants ->
        if (grants[Manifest.permission.READ_CALL_LOG] == true) {
            viewModel.importAndAnalyzePhoneCallHistory()
        }
    }

    LaunchedEffect(Unit) {
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

    val telemetryList by viewModel.telemetryList.collectAsStateWithLifecycle()
    val activeRules by viewModel.activeRules.collectAsStateWithLifecycle()
    val activeQuarantines by viewModel.activeQuarantines.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
                                text = "Ative o papel ROLE_CALL_SCREENING para interceptar chamadas em < 10 ms.",
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
                    settings = settings,
                    tailscaleStatus = tailscaleStatus,
                    onSyncNow = viewModel::syncNowWithMongoDb
                )
                1 -> CallAuditScreen(
                    telemetryList = telemetryList,
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
