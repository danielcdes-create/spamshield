package br.com.spamshield.ui.screens

import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
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
                            val sysBlockedRule = rules.firstOrNull { it.rule_id == "SYSTEM_CALLLOG_BLOCKED" }
                            val baseScoreWithoutSys = item.finalScore - (sysBlockedRule?.weight ?: 0)
                            val wasLearnedAfterRing = sysBlockedRule != null && baseScoreWithoutSys < item.thresholdApplied
                            val isBlockedAtEdge = item.outcome == "BLOCKED" && !wasLearnedAfterRing
                            val quarantineRule = rules.firstOrNull { it.rule_id == "PREFIX_QUARANTINE_HIT" }
                            val displayPrefix = quarantineRule?.meta?.split(' ')?.firstOrNull() ?: item.prefix7

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = item.normalizedNumber.ifBlank { item.rawNumber },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "${dateFormat.format(Date(item.timestamp))} • Prefixo $displayPrefix • ${item.carrierGuess ?: item.lineType}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                OutcomeBadge(
                                    outcome = item.outcome,
                                    score = item.finalScore,
                                    wasLearnedAfterRing = wasLearnedAfterRing
                                )
                            }

                            if (isBlockedAtEdge) {
                                Surface(
                                    color = Color(0xFFDC2626).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🛡️ Bloqueada na borda em ${String.format(Locale.US, "%.2f", item.processingLatencyMs)} ms (Não tocou)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFDC2626),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else if (wasLearnedAfterRing) {
                                Surface(
                                    color = Color(0xFFEA580C).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🔔 Tocou na chegada (Score inicial $baseScoreWithoutSys) ➔ Quarentenada pós-rejeição",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFEA580C),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else if (item.outcome == "ALLOWED_LOW_SCORE") {
                                Surface(
                                    color = Color(0xFF16A34A).copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "✓ Permitida em tempo real (Score ${item.finalScore} < limite ${item.thresholdApplied})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF16A34A),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (expanded) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                                if (isBlockedAtEdge) {
                                    Surface(
                                        color = Color(0xFFDC2626).copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "🛡️ Interceptação Silenciosa em Tempo Real",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                            Text(
                                                text = "Esta chamada foi interceptada e rejeitada antes de tocar no aparelho (latência: ${String.format(Locale.US, "%.2f", item.processingLatencyMs)} ms). O score atingiu ${item.finalScore} pontos (limite: ${item.thresholdApplied}), acionando o bloqueio automático de borda.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else if (wasLearnedAfterRing) {
                                    Surface(
                                        color = Color(0xFFEA580C).copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "ℹ️ Transparência de Aprendizado Ativo",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFEA580C)
                                            )
                                            Text(
                                                text = "Esta chamada tocou em tempo real porque foi a 1ª vez do prefixo (score $baseScoreWithoutSys < limite ${item.thresholdApplied}). Após ser rejeitada no discador, o SpamShield aprendeu o padrão e quarentenou o bloco de 1.000 números ($displayPrefix). Novas chamadas deste bloco serão bloqueadas em < 2 ms antes de tocar.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

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
                                val feedbackLabel = when (item.userFeedback) {
                                    "CONFIRMED_SPAM" -> "✓ SPAM CONFIRMADO PELO USUÁRIO (Prefixo ${item.prefix7} em Quarentena)"
                                    "FALSE_POSITIVE" -> "✓ FALSO POSITIVO (Prefixo ${item.prefix7} Liberado)"
                                    else -> "Ainda não avaliado (UNRATED)"
                                }
                                val feedbackColor = when (item.userFeedback) {
                                    "CONFIRMED_SPAM" -> Color(0xFFEF4444)
                                    "FALSE_POSITIVE" -> Color(0xFF22C55E)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = "Feedback: $feedbackLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = feedbackColor
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val isAlreadySpam = item.userFeedback == "CONFIRMED_SPAM"
                                    val isAlreadyLegit = item.userFeedback == "FALSE_POSITIVE"

                                    Button(
                                        onClick = {
                                            onMarkFeedback(item, "CONFIRMED_SPAM")
                                            Toast.makeText(
                                                context,
                                                "Spam confirmado! Prefixo ${item.prefix7} adicionado à quarentena.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAlreadySpam) Color(0xFF991B1B) else Color(0xFFDC2626),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (isAlreadySpam) "✓ Spam Confirmado" else "Confirmar Spam",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onMarkFeedback(item, "FALSE_POSITIVE")
                                            Toast.makeText(
                                                context,
                                                "Marcado como legítimo! Prefixo ${item.prefix7} removido da quarentena.",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                                        Text(
                                            text = if (isAlreadyLegit) "✓ Falso Positivo" else "Falso Positivo",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
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
private fun OutcomeBadge(outcome: String, score: Int, wasLearnedAfterRing: Boolean = false) {
    val (label, bgColor) = when {
        wasLearnedAfterRing -> "APRENDIDA" to Color(0xFFEA580C)
        outcome == "BLOCKED" -> "BLOQUEADA" to Color(0xFFDC2626)
        outcome == "SHADOW_BLOCKED" -> "SOMBRA" to Color(0xFFD97706)
        outcome == "ALLOWED_CONTACT" -> "AGENDA" to Color(0xFF16A34A)
        outcome == "ALLOWED_EMERGENCY_REPEAT" -> "2ª CHAMADA" to Color(0xFF16A34A)
        outcome == "ALLOWED_OUTBOUND_GRACE" -> "RETORNO" to Color(0xFF16A34A)
        else -> "PERMITIDA" to Color(0xFF16A34A)
    }
    Surface(
        color = bgColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = "$label ($score)",
            color = bgColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
