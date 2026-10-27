package br.com.spamshield.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.PrefixQuarantineEntity

@Composable
fun QuarantineRulesScreen(
    quarantines: List<PrefixQuarantineEntity>,
    rules: List<ActiveRuleEntity>,
    onAddCustomRule: (String, String, String, Int) -> Unit,
    onAddManualQuarantine: (String, String, Int) -> Unit,
    onRemoveQuarantine: (String) -> Unit,
    onToggleRule: (ActiveRuleEntity) -> Unit
) {
    var newPrefix by remember { mutableStateOf("") }
    var newCarrier by remember { mutableStateOf("VoIP / Manual") }
    var newPrefixWeight by remember { mutableStateOf("70") }

    var ruleId by remember { mutableStateOf("") }
    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("^(?:\\+55|0)?31\\d+$") }
    var ruleWeight by remember { mutableStateOf("80") }

    val now = System.currentTimeMillis()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Quarentenas de Prefixo & Regras Heurísticas",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "Clusters de prefixos de 8 dígitos (milhar) e 7 dígitos (bloco) com decaimento TTL e regras Regex.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Manual Prefix Quarantine Adder
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Adicionar Prefixo (8 ou 7 Dígitos) em Quarentena", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newPrefix,
                            onValueChange = { newPrefix = it },
                            label = { Text("Prefixo (ex: 31982042)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newPrefixWeight,
                            onValueChange = { newPrefixWeight = it },
                            label = { Text("Peso") },
                            modifier = Modifier.weight(0.5f),
                            singleLine = true
                        )
                    }
                    Button(
                        onClick = {
                            val w = newPrefixWeight.toIntOrNull() ?: 70
                            onAddManualQuarantine(newPrefix, newCarrier, w)
                            newPrefix = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Quarentenar Prefixo (TTL 7 dias)")
                    }
                }
            }
        }

        item {
            Text(
                text = "Clusters Ativos em Quarentena (${quarantines.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        items(quarantines, key = { it.prefix7 }) { q ->
            val formattedPrefix = when (q.prefix7.length) {
                8 -> "${q.prefix7.substring(0, 2)} ${q.prefix7.substring(2, 7)}-${q.prefix7.substring(7)}*"
                7 -> "${q.prefix7.substring(0, 2)} ${q.prefix7.substring(2, 6)}-${q.prefix7.substring(6)}*"
                else -> "${q.prefix7}*"
            }
            val blockScope = if (q.prefix7.length >= 8) "Milhar (1.000 nums)" else "Bloco (10.000 nums)"
            val remainingHours = ((q.expiresAt - now).coerceAtLeast(0L)) / (1000L * 3600L)
            val remainingDays = remainingHours / 24
            val remHoursMod = remainingHours % 24

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$formattedPrefix  (+${q.penaltyWeight} pts)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Hits: ${q.hitCount} • $blockScope • Operadora: ${q.carrierGuess ?: "N/D"} • TTL: ${remainingDays}d ${remHoursMod}h",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Motivo: ${q.reason}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = { onRemoveQuarantine(q.prefix7) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remover Quarentena")
                    }
                }
            }
        }

        // Custom Regex Rule Editor
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Nova Regra Regex Customizada", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ruleId,
                            onValueChange = { ruleId = it },
                            label = { Text("ID (ex: REGEX_SPAM)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = ruleWeight,
                            onValueChange = { ruleWeight = it },
                            label = { Text("Peso") },
                            modifier = Modifier.weight(0.5f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("Descrição da Regra") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rulePattern,
                        onValueChange = { rulePattern = it },
                        label = { Text("Expressão Regular (Regex)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val w = ruleWeight.toIntOrNull() ?: 80
                            onAddCustomRule(ruleId, ruleName, rulePattern, w)
                            ruleId = ""
                            ruleName = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Salvar Regra no Motor & MongoDB")
                    }
                }
            }
        }

        item {
            Text(
                text = "Regras do Motor Heurístico (${rules.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        items(rules, key = { it.ruleId }) { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${rule.name} (${if (rule.weight >= 0) "+${rule.weight}" else "${rule.weight}"})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${rule.ruleId} • ${rule.ruleType}${if (rule.pattern != null) " • ${rule.pattern}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onToggleRule(rule) }
                    )
                }
            }
        }
    }
}
