package br.com.spamshield.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.spamshield.data.local.SpamShieldSettings
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.ui.TailscaleStatusUi
import java.util.Calendar
import java.util.Locale

@Composable
fun DashboardScreen(
    telemetryList: List<CallTelemetryEntity>,
    settings: SpamShieldSettings,
    tailscaleStatus: TailscaleStatusUi,
    onSyncNow: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Global stats
    val totalIntercepted = telemetryList.size
    val blockedCalls = telemetryList.filter { it.outcome == "BLOCKED" || it.outcome == "SHADOW_BLOCKED" }
    val totalBlocked = blockedCalls.size
    val blockedPercent = if (totalIntercepted > 0) (totalBlocked * 100.0 / totalIntercepted) else 0.0
    val estimatedMinutesSaved = (totalBlocked * 45) / 60.0
    val avgLatencyMs = if (telemetryList.isNotEmpty()) {
        telemetryList.map { it.processingLatencyMs }.average()
    } else {
        0.0
    }

    // Today's stats (from 00:00 today)
    val startOfTodayMillis = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todayCalls = telemetryList.filter { it.timestamp >= startOfTodayMillis }
    val todayIntercepted = todayCalls.size
    val todayBlocked = todayCalls.count { it.outcome == "BLOCKED" || it.outcome == "SHADOW_BLOCKED" }
    val todayMinutesSaved = (todayBlocked * 45) / 60.0
    val todayTopCarrier = todayCalls
        .filter { it.outcome == "BLOCKED" || it.outcome == "SHADOW_BLOCKED" }
        .groupBy { classifyCarrierLabel(it) }
        .maxByOrNull { it.value.size }
        ?.key ?: "Nenhuma"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Capa Moderna (Hero Card) com Nome do App + Status Tailscale + Stats do Dia
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0B1528),
                            Color(0xFF172554),
                            Color(0xFF0F3D3E)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.6f), Color(0xFF22C55E).copy(alpha = 0.4f))
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SpamShield BR",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 21.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (settings.shadowModeEnabled) {
                                    "Modo Sombra Ativo • Anatel & STIR/SHAKEN"
                                } else {
                                    "Escudo Ativo (< 10 ms) • Anatel & STIR/SHAKEN"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    IconButton(
                        onClick = onSyncNow,
                        enabled = !tailscaleStatus.isSyncing
                    ) {
                        if (tailscaleStatus.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF38BDF8)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Sincronizar",
                                tint = Color(0xFF38BDF8)
                            )
                        }
                    }
                }

                // Status Tailscale compacto
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isOnline = tailscaleStatus.isServerOnline || tailscaleStatus.isVpnActive
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) Color(0xFF22C55E) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tailscale (${tailscaleStatus.detectedIp}) • ${tailscaleStatus.lastSyncMessage}",
                            fontSize = 11.sp,
                            color = Color(0xFFE2E8F0),
                            maxLines = 1
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                // Resumo / Stats do Dia na Capa
                Text(
                    text = "RESUMO DE HOJE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroDailyStatItem(
                        label = "Avaliadas Hoje",
                        value = "$todayIntercepted",
                        accent = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    HeroDailyStatItem(
                        label = "Bloqueadas Hoje",
                        value = "$todayBlocked",
                        accent = Color(0xFFEF4444),
                        modifier = Modifier.weight(1f)
                    )
                    HeroDailyStatItem(
                        label = "Salvos Hoje",
                        value = String.format(Locale.US, "%.1fm", todayMinutesSaved),
                        accent = Color(0xFF22C55E),
                        modifier = Modifier.weight(1f)
                    )
                    HeroDailyStatItem(
                        label = "Maior Origem",
                        value = todayTopCarrier,
                        accent = Color(0xFFF59E0B),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Cards de Resumo Acumulado (KPIs Globais)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KpiCard(
                title = "Total Interceptado",
                value = "$totalIntercepted",
                subtitle = "Histórico + Tempo Real",
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "Total Bloqueado",
                value = String.format(Locale.US, "%d (%.0f%%)", totalBlocked, blockedPercent),
                subtitle = "Threshold >= ${settings.blockThresholdScore}",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KpiCard(
                title = "Tempo Economizado",
                value = String.format(Locale.US, "%.1f min", estimatedMinutesSaved),
                subtitle = "~45s / robocall",
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                title = "Latência Média",
                value = String.format(Locale.US, "%.2f ms", avgLatencyMs),
                subtitle = "Meta Edge < 10 ms",
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Gráfico Nativo 1: Barras Horizontais — Bloqueios por Categoria
        CategoryHorizontalBarChartCard(telemetryList = telemetryList)

        // 4. Gráfico Nativo 2: Rosca/Pizza — Proporção por Operadora (Claro e VoIP separados)
        CarrierDonutChartCard(telemetryList = telemetryList)

        // 5. Gráfico Nativo 3: Heatmap Horário (00h às 23h)
        HourlyHeatmapCard(telemetryList = telemetryList)
    }
}

private fun classifyCarrierLabel(item: CallTelemetryEntity): String {
    val c = item.carrierGuess?.uppercase()?.trim() ?: ""
    return when {
        c == "FIXO" -> "Fixo"
        c == "VOIP" -> "VoIP"
        item.lineType == "TOLL_FREE_OR_SPECIAL" || c.contains("PABX") -> "VoIP"
        item.lineType == "FIXED_OR_VOIP" || c.contains("VOIP") || c.contains("FIXO") -> {
            if (br.com.spamshield.domain.PhoneNormalizer.isVoipPrefixOrSubscriber(item.prefix7)) {
                "VoIP"
            } else {
                "Fixo"
            }
        }
        c.contains("CLARO") -> "Claro"
        c.contains("TIM") -> "TIM"
        c.contains("VIVO") -> "Vivo"
        else -> "Outros"
    }
}

@Composable
private fun HeroDailyStatItem(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = accent,
            maxLines = 1
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFFCBD5E1),
            maxLines = 1
        )
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryHorizontalBarChartCard(telemetryList: List<CallTelemetryEntity>) {
    val categories = listOf(
        "ROBOCALL_PREFIX_CLUSTER" to Color(0xFFEF4444),
        "SPOOFING_STIR_FAILED" to Color(0xFFF97316),
        "PING_CALL_ROBOCALL" to Color(0xFFEAB308),
        "KNOWN_BLACKLIST" to Color(0xFF8B5CF6)
    )
    val counts = categories.map { (cat, color) ->
        Triple(cat, telemetryList.count { it.category == cat }, color)
    }
    val maxCount = (counts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Bloqueios por Categoria",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            counts.forEach { (cat, count, color) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = cat, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                    ) {
                        drawRoundRect(
                            color = Color.LightGray.copy(alpha = 0.25f),
                            size = size,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        val fraction = (count.toFloat() / maxCount.toFloat()).coerceIn(0.02f, 1f)
                        drawRoundRect(
                            color = color,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarrierDonutChartCard(telemetryList: List<CallTelemetryEntity>) {
    val carrierGroups = telemetryList.groupBy { classifyCarrierLabel(it) }
    val slices = listOf(
        Triple("Claro", carrierGroups["Claro"]?.size ?: 0, Color(0xFFEF4444)),
        Triple("VoIP", carrierGroups["VoIP"]?.size ?: 0, Color(0xFFF59E0B)),
        Triple("TIM", carrierGroups["TIM"]?.size ?: 0, Color(0xFF3B82F6)),
        Triple("Vivo", carrierGroups["Vivo"]?.size ?: 0, Color(0xFF8B5CF6)),
        Triple("Fixo", carrierGroups["Fixo"]?.size ?: 0, Color(0xFF06B6D4)),
        Triple("Outros", carrierGroups["Outros"]?.size ?: 0, Color(0xFF10B981))
    )
    val total = slices.sumOf { it.second }.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Proporção por Operadora Detectada",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Canvas(modifier = Modifier.size(110.dp)) {
                    var startAngle = -90f
                    if (telemetryList.isEmpty()) {
                        drawArc(
                            color = Color.Gray.copy(alpha = 0.3f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 26f, cap = StrokeCap.Butt)
                        )
                    } else {
                        slices.forEach { (_, count, color) ->
                            val sweep = (count.toFloat() / total.toFloat()) * 360f
                            if (sweep > 0f) {
                                drawArc(
                                    color = color,
                                    startAngle = startAngle,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    style = Stroke(width = 26f, cap = StrokeCap.Butt)
                                )
                                startAngle += sweep
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { (name, count, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$name: $count",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyHeatmapCard(telemetryList: List<CallTelemetryEntity>) {
    val hourCounts = IntArray(24)
    val cal = Calendar.getInstance()
    telemetryList.forEach { item ->
        cal.timeInMillis = item.timestamp
        val h = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
        hourCounts[h]++
    }
    val maxHour = (hourCounts.maxOrNull() ?: 1).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Heatmap Horário de Ataques (00h às 23h)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val cellWidth = size.width / 24f
                for (h in 0 until 24) {
                    val count = hourCounts[h]
                    val intensity = if (count == 0) {
                        0.12f
                    } else {
                        (0.25f + 0.75f * (count.toFloat() / maxHour.toFloat())).coerceIn(0.25f, 1f)
                    }
                    val barHeight = size.height * intensity
                    drawRoundRect(
                        color = if (count == 0) Color.Gray.copy(alpha = 0.25f) else Color(0xFFEF4444).copy(alpha = intensity),
                        topLeft = Offset(h * cellWidth + 2f, size.height - barHeight),
                        size = Size((cellWidth - 4f).coerceAtLeast(2f), barHeight),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("00h", style = MaterialTheme.typography.labelSmall)
                Text("06h", style = MaterialTheme.typography.labelSmall)
                Text("12h", style = MaterialTheme.typography.labelSmall)
                Text("18h", style = MaterialTheme.typography.labelSmall)
                Text("23h", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
