package br.com.spamshield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.spamshield.data.local.SpamShieldSettings
import br.com.spamshield.ui.ApkUpdateUiState
import br.com.spamshield.ui.TailscaleStatusUi
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: SpamShieldSettings,
    tailscaleStatus: TailscaleStatusUi,
    apkUpdateState: ApkUpdateUiState,
    onToggleShadowMode: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onImportCallHistory: () -> Unit,
    onSyncAnatelProviders: () -> Unit,
    onSimulateAttack: (String, String) -> Unit,
    onSaveTailscaleUrl: (String) -> Unit,
    onChangeThreshold: (Int) -> Unit,
    onToggleSkipCallLog: (Boolean) -> Unit,
    onCheckApkUpdate: () -> Unit,
    onDownloadAndInstallApk: (android.content.Context) -> Unit,
    onExportFiles: suspend () -> Pair<File, File>
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var urlText by remember(settings.tailscaleServerUrl) {
        mutableStateOf(settings.tailscaleServerUrl)
    }
    var sliderValue by remember(settings.blockThresholdScore) {
        mutableFloatStateOf(settings.blockThresholdScore.toFloat())
    }
    var exportMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Configurações, Operações & Tailscale",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        // 1. Operações de Sincronização, Histórico, Provedores Anatel e Modo Sombra (Movido da capa)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Operações de Sincronização & Inteligência",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Status: ${tailscaleStatus.lastSyncMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Modo Sombra (Dry-Run)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (settings.shadowModeEnabled) {
                                "Ativo: deixa tocar e envia notificação de auditoria"
                            } else {
                                "Inativo: bloqueio silencioso em < 10 ms"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.shadowModeEnabled,
                        onCheckedChange = onToggleShadowMode
                    )
                }

                Button(
                    onClick = onSyncNow,
                    enabled = !tailscaleStatus.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (tailscaleStatus.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudSync, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sincronizar Agora com MongoDB")
                }

                Button(
                    onClick = onImportCallHistory,
                    enabled = !tailscaleStatus.isImportingHistory,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color(0xFF052E16)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (tailscaleStatus.isImportingHistory) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF052E16)
                        )
                    } else {
                        Icon(Icons.Default.History, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recuperar e Analisar Histórico do Telefone",
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onSyncAnatelProviders,
                    enabled = !tailscaleStatus.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Atualizar Base Anatel & Blacklists BR (0303/VoIP)")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { onSimulateAttack("03141011230", "FAILED") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Simular VoIP 31 4101", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { onSimulateAttack("+553198621040", "NOT_VERIFIED") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Simular Robocall TIM", fontSize = 11.sp)
                    }
                }
            }
        }

        // 2. Atualização OTA de APK via Tailscale (Formatação corrigida com weight(1f) e badge sem quebra)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Atualização do App (APK Tailscale)",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "v${apkUpdateState.installedVersionName} (${apkUpdateState.installedVersionCode})",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = apkUpdateState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (apkUpdateState.hasNewerVersion) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (apkUpdateState.isDownloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCheckApkUpdate,
                        enabled = !apkUpdateState.isChecking && !apkUpdateState.isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (apkUpdateState.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Verificar Atualização", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onDownloadAndInstallApk(context) },
                        enabled = !apkUpdateState.isDownloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (apkUpdateState.hasNewerVersion) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Atualizar APK", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Endpoint Tailscale do Servidor Rust
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Endpoint Tailscale (Servidor Rust Axum)",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IP Tailscale local: http://100.83.253.15:8080 (ou MagicDNS http://gbp360.tail1093e7.ts.net:8080)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("URL do Servidor Rust") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveTailscaleUrl(urlText) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Salvar Endpoint")
                    }
                    OutlinedButton(
                        onClick = {
                            urlText = "http://100.83.253.15:8080"
                            onSaveTailscaleUrl(urlText)
                        }
                    ) {
                        Text("Usar 100.83.253.15")
                    }
                }
            }
        }

        // 4. Slider de Threshold Score (Padrão 70)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Threshold Score de Bloqueio",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${sliderValue.roundToInt()} pts",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Chamadas com pontuação igual ou superior a este limite são bloqueadas (padrão recomendado: 70).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onChangeThreshold(sliderValue.roundToInt()) },
                    valueRange = 30f..120f
                )
            }
        }

        // 5. Toggle Ocultar do Histórico Nativo (setSkipCallLog)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ocultar Bloqueios do Histórico do Telefone",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Aciona setSkipCallLog(true) no CallScreeningService para manter o registro nativo limpo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.hideBlockedFromSystemCallLog,
                    onCheckedChange = onToggleSkipCallLog
                )
            }
        }

        // 6. Exportação Local em JSON / CSV
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Exportação Local de Telemetria (JSON & CSV)",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Exporta todo o banco SQLite Room local para auditoria offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        scope.launch {
                            val (jsonFile, csvFile) = onExportFiles()
                            exportMessage = "Exportado com sucesso:\n• ${jsonFile.absolutePath}\n• ${csvFile.absolutePath}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exportar Banco Local (JSON / CSV)")
                }
                if (exportMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = exportMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
