package br.com.spamshield.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.spamshield.data.local.entity.ActiveRuleEntity
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.domain.DecisionTrace
import br.com.spamshield.domain.ExpectedWindow
import br.com.spamshield.domain.FeatureJournal
import br.com.spamshield.domain.LabExperiment
import br.com.spamshield.domain.RuleChange
import br.com.spamshield.domain.RuleLaboratory
import br.com.spamshield.domain.ScreeningLatency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun displayTime(at: Long, pattern: String = "dd/MM HH:mm"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

internal fun maskedNumber(number: String): String {
    val digits = number.filter(Char::isDigit)
    return if (digits.length >= 8) "${digits.take(4)}••••" else "Número oculto"
}

internal fun ScreeningLatency.elapsedLabel(): String? {
    val stages = listOf("fila" to evaluationWaitMs, "contexto" to localContextMs,
        "regras" to rulesMs, "política" to policyMs, "envio" to submissionMs,
        "diário" to journalMs, "candidata" to candidateMs)
    return stages.mapNotNull { (name, value) ->
        value?.let { "$name %.1f ms".format(it) }
    }.joinToString(" · ").ifEmpty { null }
}

internal fun durationLabel(minutes: Int) = when (minutes) {
    60 -> "1 hora"
    120 -> "2 horas"
    else -> "$minutes min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpectedCallSelector(
    active: ExpectedWindow?, dismiss: () -> Unit,
    change: (Int?, Boolean, Boolean) -> Unit
) {
    var keepExact by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Por quanto tempo você está esperando uma ligação?", fontWeight = FontWeight.Bold)
            if (active != null) {
                Text("Aguardando ligação até ${displayTime(active.expiresAt, "HH'h'mm")}. Uma nova duração começa agora.")
                TextButton(onClick = { change(null, true, true) }) { Text("Restaurar proteção agora") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manter bloqueio por quarentena exata", modifier = Modifier.weight(1f))
                Switch(keepExact, { keepExact = it })
            }
            for (duration in ExpectedWindow.durations) {
                Button(onClick = { change(duration, keepExact, false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(durationLabel(duration)) }
            }
        }
    }
}

internal fun submittedResponse(events: List<CallDecisionEventEntity>) = events.lastOrNull {
    it.source == "CALL_SCREENING_SERVICE" && it.eventType == "SCREENING_RESPONSE_SUBMITTED"
}?.let(DecisionTrace::details)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleLab(
    rules: List<ActiveRuleEntity>, events: List<FeatureEventEntity>, callEvents: List<CallDecisionEventEntity>, dismiss: () -> Unit,
    start: (RuleChange, Int?) -> Unit, finish: (LabExperiment, String) -> Unit,
    busy: Boolean, error: String?
) {
    val catalog = remember(rules) { RuleLaboratory.catalog(rules) }
    var selection by remember { mutableStateOf<ActiveRuleEntity?>(null) }
    var weight by remember { mutableStateOf("") }
    var disabled by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(30) }
    var selectedExperiment by remember { mutableStateOf<String?>(null) }
    var apply by remember { mutableStateOf<LabExperiment?>(null) }
    val experiments = remember(events) {
        events.filter { it.eventType in setOf("LAB_REPLAY_STARTED", "LAB_SHADOW_STARTED") }.asReversed()
            .mapNotNull { FeatureJournal.payload(it).experiment }
    }
    val experiment = experiments.find { it.id == selectedExperiment } ?: experiments.firstOrNull()
    val history = events.filter { it.resourceId == experiment?.id }
    val comparisons = remember(history) { history.mapNotNull { FeatureJournal.payload(it).comparison }.distinctBy { it.originalEventId } }
    val proposedWeight = weight.toIntOrNull()?.takeIf { it in -1000..1000 }
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Testar alteração", fontWeight = FontWeight.Bold)
                val chosen = selection
                if (chosen != null) {
                    TextButton(onClick = { selection = null }) { Text(chosen.name + " · trocar regra") }
                    OutlinedTextField(weight, { weight = it }, label = { Text("Peso proposto") }, singleLine = true,
                        isError = proposedWeight == null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(disabled, { disabled = it })
                        Text("Desativar regra")
                    }
                    val change = proposedWeight?.let { RuleChange(chosen.ruleId, it, !disabled) }
                    Button(enabled = change != null && !busy, onClick = { change?.let { start(it, null); selectedExperiment = null } }) {
                        Text("Reavaliar histórico")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (minutes in ExpectedWindow.durations) {
                            FilterChip(duration == minutes, { duration = minutes }, { Text(durationLabel(minutes)) })
                        }
                    }
                    Button(enabled = change != null && !busy, onClick = { change?.let { start(it, duration); selectedExperiment = null } }) {
                        Text("Testar nas próximas chamadas")
                    }
                } else Text("Selecione uma regra.")
                if (busy) Text("Processando…")
                if (error != null) Text(error)
            }
            if (selection == null) {
                items(catalog, key = { it.ruleId }) { rule ->
                    TextButton(onClick = { selection = rule; weight = rule.weight.toString(); disabled = !rule.enabled }) {
                        Text("${rule.name} · ${rule.weight}")
                    }
                }
            }
            if (experiments.isNotEmpty()) {
                item { Text("Experimentos", fontWeight = FontWeight.Bold) }
                items(experiments, key = { it.id }) { run ->
                    TextButton(onClick = { selectedExperiment = run.id }) {
                        Text("${displayTime(run.startedAt)} · ${if (run.expiresAt == null) "Replay" else "Sombra"} · ${run.change.ruleId}")
                    }
                }
            }
            if (experiment != null) {
                item {
                    val status = history.lastOrNull { it.eventType != "LAB_COMPARISON" }?.eventType
                    val terminal = status in setOf("LAB_APPLIED", "LAB_DISCARDED")
                    val active = RuleLaboratory.active(events, System.currentTimeMillis())?.id == experiment.id
                    Text("Resultados · ${experiment.change.ruleId}", fontWeight = FontWeight.Bold)
                    Text(if (experiment.expiresAt == null) "Replay do histórico" else
                        "Sombra · ${displayTime(experiment.startedAt)} a ${displayTime(experiment.expiresAt)}")
                    val linked = if (experiment.expiresAt == null) comparisons.size else
                        callEvents.asSequence().filter { it.source == "CALL_SCREENING_SERVICE" &&
                            DecisionTrace.details(it).experimentId == experiment.id }.map { it.callId }.distinct().count()
                            .coerceAtLeast(comparisons.size)
                    Text("${comparisons.size} de $linked triagens comparadas · ${comparisons.count { it.direction != "INSUFFICIENT" }} com evidência suficiente")
                    RuleLaboratory.directions.forEach { direction ->
                        Text("${labDirection(direction)} · ${comparisons.count { it.direction == direction }}")
                    }
                    comparisons.groupBy { it.baseFingerprint }.forEach { (base, group) ->
                        Text("Base ${base?.take(12) ?: "indisponível"} · " + group.groupingBy { labDirection(it.direction) }.eachCount()
                            .entries.joinToString { "${it.key}: ${it.value}" })
                    }
                    if (active) TextButton(enabled = !busy, onClick = { finish(experiment, "LAB_ENDED") }) { Text("Encerrar") }
                    if (!terminal) {
                        TextButton(enabled = !busy, onClick = { finish(experiment, "LAB_DISCARDED") }) { Text("Descartar") }
                        TextButton(enabled = !busy, onClick = { apply = experiment }) { Text("Aplicar alteração") }
                    }
                    if (terminal) Text(if (status == "LAB_APPLIED") "Alteração aplicada" else "Alteração descartada")
                }
                items(comparisons, key = { it.originalEventId }) { comparison ->
                    var expanded by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
                        Text("${labDirection(comparison.direction)} · ${comparison.baseScore ?: "?"} → ${comparison.candidateScore ?: "?"}")
                        if (expanded) {
                            Text("Limiar ${comparison.threshold ?: "indisponível"} · ${labReason(comparison.reason)}")
                            Text("Base: " + comparison.baseRules.joinToString { "${it.rule_id} (${it.weight})" })
                            Text("Candidata: " + comparison.candidateRules.joinToString { "${it.rule_id} (${it.weight})" })
                        }
                    }
                }
            }
        }
    }
    apply?.let { run ->
        AlertDialog(onDismissRequest = { apply = null }, title = { Text("Aplicar à proteção ativa?") },
            text = { Text("${run.change.ruleId} · ${if (run.change.enabled) "peso ${run.change.weight}" else "desativada"}") },
            confirmButton = { TextButton(onClick = { finish(run, "LAB_APPLIED"); apply = null }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { apply = null }) { Text("Cancelar") } })
    }
}

internal fun labReason(reason: String): String = when (reason) {
    "ARRIVAL_CONTEXT_UNAVAILABLE" -> "Contexto original indisponível"
    "RULE_CONFIGURATION_UNAVAILABLE" -> "Regra ausente na configuração original"
    "PREFIX_MATCH_UNAVAILABLE" -> "Correspondência de prefixo indisponível"
    "BASE_RECONSTRUCTION_MISMATCH" -> "A reconstrução divergiu da decisão original"
    "AMBIGUOUS_ARRIVAL_DECISION" -> "Mais de uma decisão original vinculada à chamada"
    "CANDIDATE_RECONSTRUCTION_FAILED" -> "Não foi possível aplicar a candidata ao contexto original"
    "PROTECTED_ALLOW" -> "Permissão protegida"
    else -> reason
}

private fun labDirection(value: String): String = when (value) {
    "BLOCK_TO_ALLOW" -> "Bloquear → permitir"
    "ALLOW_TO_BLOCK" -> "Permitir → bloquear"
    "UNCHANGED" -> "Sem mudança de ação"
    else -> "Evidência insuficiente"
}
