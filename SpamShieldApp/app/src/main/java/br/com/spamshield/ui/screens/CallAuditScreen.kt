package br.com.spamshield.ui.screens

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallAuditScreen(
    telemetryList: List<CallTelemetryEntity>,
    parseTriggeredRules: (String) -> List<TriggeredRuleDto>,
    onMarkFeedback: (CallTelemetryEntity, String) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Auditoria de Chamadas (Raio-X em Tempo Real)",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            text = "Toque em uma chamada para expandir a Árvore de Decisão e calibrar o motor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (telemetryList.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Nenhuma chamada interceptada ainda.",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Use os botões de simulação no Dashboard ou aguarde chamadas reais.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(telemetryList, key = { it.id }) { item ->
                    var expanded by remember { mutableStateOf(false) }
                    val rules = remember(item.triggeredRulesJson) {
                        parseTriggeredRules(item.triggeredRulesJson)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = item.normalizedNumber.ifBlank { item.rawNumber },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "${dateFormat.format(Date(item.timestamp))} • Prefixo ${item.prefix7} • ${item.carrierGuess ?: item.lineType}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                OutcomeBadge(outcome = item.outcome, score = item.finalScore)
                            }

                            if (expanded) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                Text(
                                    text = "Árvore de Decisão Vetorial",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "STIR/SHAKEN: ${item.stirShakenStatus} | Categoria: ${item.category} | Latência: ${String.format(Locale.US, "%.2f", item.processingLatencyMs)} ms | Sync: ${item.syncStatus}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                rules.forEach { rule ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "• ${rule.rule_id}${if (rule.meta != null) " (${rule.meta})" else ""}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        val sign = if (rule.weight >= 0) "+${rule.weight}" else "${rule.weight}"
                                        Text(
                                            text = sign,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (rule.weight >= 0) Color(0xFFEF4444) else Color(0xFF22C55E)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Feedback Atual: ${item.userFeedback}",
                                    style = MaterialTheme.typography.labelSmall
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onMarkFeedback(item, "CONFIRMED_SPAM") },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFDC2626)
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Confirmar Spam", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onMarkFeedback(item, "FALSE_POSITIVE")
                                            // Abre opção de salvar nos contatos
                                            runCatching {
                                                val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                                    type = ContactsContract.RawContacts.CONTENT_TYPE
                                                    putExtra(ContactsContract.Intents.Insert.PHONE, item.normalizedNumber)
                                                }
                                                context.startActivity(insertIntent)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Falso Positivo", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeBadge(outcome: String, score: Int) {
    val bgColor = when (outcome) {
        "BLOCKED" -> Color(0xFFDC2626)
        "SHADOW_BLOCKED" -> Color(0xFFD97706)
        else -> Color(0xFF16A34A)
    }
    Surface(
        color = bgColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = "$outcome ($score)",
            color = bgColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
