package br.com.spamshield.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.spamshield.data.local.entity.CallDecisionEventEntity
import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.FeatureEventEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import br.com.spamshield.domain.Campaign
import br.com.spamshield.domain.CampaignSilence
import br.com.spamshield.domain.Campaigns
import br.com.spamshield.domain.DecisionTrace
import br.com.spamshield.domain.ExpectedWindow
import br.com.spamshield.domain.FeatureJournal
import br.com.spamshield.domain.TelemetryProvenance
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface AuditFeedItem {
    val sortTimestamp: Long
    val itemKey: String

    data class CallItem(val telemetry: CallTelemetryEntity) : AuditFeedItem {
        override val sortTimestamp: Long get() = telemetry.timestamp
        override val itemKey: String get() = "call-${telemetry.id}"
    }

    data class CampaignItem(
        val campaign: Campaign,
        val members: List<CallTelemetryEntity>
    ) : AuditFeedItem {
        override val sortTimestamp: Long
            get() = maxOf(campaign.lastCallAt, members.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE)
        override val itemKey: String get() = "campaign-${campaign.id}"
    }
}

internal fun buildAuditFeedItems(
    telemetryList: List<CallTelemetryEntity>,
    campaigns: List<Campaign>
): List<AuditFeedItem> {
    val telemetryById = telemetryList.associateBy { it.id }
    val claimedCallIds = HashSet<String>()
    val campaignItems = buildList {
        for (campaign in campaigns.sortedWith(compareByDescending<Campaign> { it.lastCallAt }.thenBy { it.id })) {
            val members = campaign.memberIds
                .distinct()
                .mapNotNull { telemetryById[it] }
                .filter { it.id !in claimedCallIds }
                .sortedWith(compareByDescending<CallTelemetryEntity> { it.timestamp }.thenBy { it.id })
            if (members.isNotEmpty()) {
                members.forEach { claimedCallIds.add(it.id) }
                add(AuditFeedItem.CampaignItem(campaign = campaign, members = members))
            }
        }
    }
    val standaloneItems = telemetryList
        .filter { it.id !in claimedCallIds }
        .map { AuditFeedItem.CallItem(it) }

    return (campaignItems + standaloneItems).sortedWith(
        compareByDescending<AuditFeedItem> { it.sortTimestamp }.thenBy { it.itemKey }
    )
}

@Composable
fun CallAuditScreen(
    telemetryList: List<CallTelemetryEntity>,
    decisionEvents: List<CallDecisionEventEntity>,
    parseTriggeredRules: (String) -> List<TriggeredRuleDto>,
    onMarkFeedback: (CallTelemetryEntity, String) -> Unit,
    campaigns: List<Campaign>,
    featureEvents: List<FeatureEventEntity>,
    onSilence: (String, Int?) -> Unit,
    onExpectedFeedback: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val eventsByCall = remember(decisionEvents) { decisionEvents.groupBy { it.callId } }
    val featuresByCall = remember(featureEvents) { featureEvents.filter { it.callId != null }.groupBy { it.callId } }
    val allSilences = remember(featureEvents) { Campaigns.silences(featureEvents) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(allSilences) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            val nextExpiry = allSilences.filter { it.activeAt(nowMillis) }.minOfOrNull { it.expiresAt } ?: break
            val waitMs = (nextExpiry - nowMillis).coerceAtLeast(0L)
            delay(waitMs + 50L)
        }
    }
    val feedItems = remember(telemetryList, campaigns) { buildAuditFeedItems(telemetryList, campaigns) }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Auditoria de chamadas",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            text = "Toque em uma chamada ou campanha para expandir os detalhes e calibrar o motor.",
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
                items(feedItems, key = { it.itemKey }) { feedItem ->
                    when (feedItem) {
                        is AuditFeedItem.CampaignItem -> {
                            val activeSilence = allSilences.lastOrNull {
                                it.prefix == feedItem.campaign.prefix && it.activeAt(nowMillis)
                            }
                            CampaignAuditCard(
                                feedItem = feedItem,
                                activeSilence = activeSilence,
                                eventsByCall = eventsByCall,
                                featuresByCall = featuresByCall,
                                dateFormat = dateFormat,
                                context = context,
                                parseTriggeredRules = parseTriggeredRules,
                                onMarkFeedback = onMarkFeedback,
                                onSilence = onSilence,
                                onExpectedFeedback = onExpectedFeedback
                            )
                        }
                        is AuditFeedItem.CallItem -> {
                            var expanded by rememberSaveable(feedItem.telemetry.id) { mutableStateOf(false) }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                CallAuditCardBody(
                                    item = feedItem.telemetry,
                                    callEvents = eventsByCall[feedItem.telemetry.id].orEmpty(),
                                    callFeatureEvents = featuresByCall[feedItem.telemetry.id].orEmpty(),
                                    expanded = expanded,
                                    includeCampaignRequestLabel = false,
                                    dateFormat = dateFormat,
                                    context = context,
                                    parseTriggeredRules = parseTriggeredRules,
                                    onMarkFeedback = onMarkFeedback,
                                    onExpectedFeedback = onExpectedFeedback
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignAuditCard(
    feedItem: AuditFeedItem.CampaignItem,
    activeSilence: CampaignSilence?,
    eventsByCall: Map<String, List<CallDecisionEventEntity>>,
    featuresByCall: Map<String?, List<FeatureEventEntity>>,
    dateFormat: SimpleDateFormat,
    context: Context,
    parseTriggeredRules: (String) -> List<TriggeredRuleDto>,
    onMarkFeedback: (CallTelemetryEntity, String) -> Unit,
    onSilence: (String, Int?) -> Unit,
    onExpectedFeedback: (String, Boolean) -> Unit
) {
    val campaign = feedItem.campaign
    val members = feedItem.members
    var expanded by rememberSaveable(campaign.id) { mutableStateOf(false) }
    var selectingDuration by rememberSaveable(campaign.id) { mutableStateOf(false) }

    val memberEvidences = remember(members, eventsByCall) {
        members.associate { it.id to DecisionTrace.evidence(it, eventsByCall[it.id].orEmpty()) }
    }
    val blockRequestedCount = memberEvidences.values.count { it.blockRequested }
    val blockObservedCount = memberEvidences.values.count { it.blockObserved }
    val distinctNumbersCount = remember(members, campaign.distinctNumbers) {
        members.map { it.normalizedNumber }.filter { it.isNotBlank() }.distinct().size
            .takeIf { it > 0 } ?: campaign.distinctNumbers
    }
    val accentColor = if (activeSilence != null) Color(0xFFF59E0B) else Color(0xFF8B5CF6)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f))
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "Campanha ${campaign.prefix}••",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${dateFormat.format(Date(feedItem.sortTimestamp))} • ${members.size} tentativas • $distinctNumbersCount números distintos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                CampaignBadge(
                    attemptCount = members.size,
                    isSilenced = activeSilence != null
                )
            }

            val silenceNote = if (activeSilence != null) {
                " • Silenciada até ${displayTime(activeSilence.expiresAt, "HH:mm")}"
            } else {
                ""
            }
            Text(
                text = "$blockRequestedCount pedidos de bloqueio • $blockObservedCount confirmados pelo Android$silenceNote",
                style = MaterialTheme.typography.bodySmall,
                color = if (activeSilence != null) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                Text(
                    text = "Detalhes da campanha",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Período: ${dateFormat.format(Date(campaign.firstCallAt))} até ${dateFormat.format(Date(campaign.lastCallAt))}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Critério v${campaign.criterionVersion}: três ou mais números distintos da mesma faixa em até cinco minutos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (activeSilence != null) {
                    Text(
                        text = "Silenciamento ativo até ${displayTime(activeSilence.expiresAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF59E0B)
                    )
                    OutlinedButton(onClick = { onSilence(campaign.id, null) }) {
                        Text("Desfazer silenciamento", fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(onClick = { selectingDuration = !selectingDuration }) {
                        Text("Silenciar próximas chamadas desta campanha", fontSize = 12.sp)
                    }
                    if (selectingDuration) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ExpectedWindow.durations.forEach { minutes ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onSilence(campaign.id, minutes)
                                        selectingDuration = false
                                    },
                                    label = { Text(durationLabel(minutes), fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                Text(
                    text = "Chamadas desta campanha (${members.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { memberCall ->
                        key(memberCall.id) {
                            var memberExpanded by rememberSaveable(memberCall.id) { mutableStateOf(false) }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { memberExpanded = !memberExpanded },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                            ) {
                                CallAuditCardBody(
                                    item = memberCall,
                                    callEvents = eventsByCall[memberCall.id].orEmpty(),
                                    callFeatureEvents = featuresByCall[memberCall.id].orEmpty(),
                                    expanded = memberExpanded,
                                    includeCampaignRequestLabel = true,
                                    dateFormat = dateFormat,
                                    context = context,
                                    parseTriggeredRules = parseTriggeredRules,
                                    onMarkFeedback = onMarkFeedback,
                                    onExpectedFeedback = onExpectedFeedback
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallAuditCardBody(
    item: CallTelemetryEntity,
    callEvents: List<CallDecisionEventEntity>,
    callFeatureEvents: List<FeatureEventEntity>,
    expanded: Boolean,
    includeCampaignRequestLabel: Boolean,
    dateFormat: SimpleDateFormat,
    context: Context,
    parseTriggeredRules: (String) -> List<TriggeredRuleDto>,
    onMarkFeedback: (CallTelemetryEntity, String) -> Unit,
    onExpectedFeedback: (String, Boolean) -> Unit
) {
    val rules = remember(item.triggeredRulesJson) {
        parseTriggeredRules(item.triggeredRulesJson)
    }
    val evidence = remember(item, callEvents) { DecisionTrace.evidence(item, callEvents) }
    val provenance = remember(item, callEvents) { TelemetryProvenance.forCall(item, callEvents) }
    val displayPrefix = item.prefix7.takeIf { it.length in 7..8 && it.all(Char::isDigit) } ?: "sem prefixo"
    val response = remember(callEvents) { submittedResponse(callEvents) }

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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
                    text = maskedNumber(item.normalizedNumber.ifBlank { item.rawNumber }),
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
                outcome = evidence.label,
                score = item.finalScore,
                wasLearnedAfterRing = evidence.dialerRejected
            )
        }

        val requestPrefix = if (includeCampaignRequestLabel) {
            val req = when {
                evidence.blockRequested -> "Bloqueio solicitado"
                response?.silenceCall == true -> "Silenciamento solicitado"
                response != null -> "Permissão enviada"
                else -> "Resposta indisponível"
            }
            "$req • "
        } else {
            ""
        }

        Text(
            text = "$requestPrefix${evidence.label} • Score de chegada: ${evidence.arrivalScore ?: "não registrado"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Versão na chegada: ${provenance.originalDecision?.appVersion?.label ?: "não registrada"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (expanded) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            val policy = response?.policy
            if (policy?.reason == "EXPECTED_CALL_WINDOW") {
                Text("Permissão enviada pela janela de ligação esperada. A política normal bloquearia.")
                Text("Sinais considerados: ${policy.windowSignals.joinToString()}")
                val feedback = callFeatureEvents.lastOrNull { it.eventType == "WAIT_FEEDBACK" }
                    ?.let(FeatureJournal::payload)?.reason
                Text(
                    when (feedback) {
                        "EXPECTED_CALL_CONFIRMED" -> "Você confirmou que era a ligação esperada."
                        "SPAM_CONFIRMED" -> "Você confirmou que era spam."
                        else -> "Sem avaliação da janela."
                    }
                )
                TextButton(onClick = { onExpectedFeedback(item.id, true) }) { Text("Era a ligação esperada") }
                TextButton(onClick = { onExpectedFeedback(item.id, false) }) { Text("Era spam") }
            }
            callFeatureEvents.mapNotNull { FeatureJournal.payload(it).comparison }.forEach {
                Text("Laboratório · ${it.baseScore ?: "?"} → ${it.candidateScore ?: "?"} · ${labReason(it.reason)}")
            }
            Text("Linha do tempo da chamada", fontWeight = FontWeight.Bold)
            Text(
                "Data da chamada: ${dateFormat.format(Date(item.timestamp))}. Os horários abaixo são de gravação dos eventos; podem ser posteriores à chamada.",
                style = MaterialTheme.typography.bodySmall
            )
            if (provenance.originalDecision == null) {
                Text(
                    "A versão na chegada é desconhecida. Versões de importação, reavaliação e exportação não identificam a versão que recebeu a chamada.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (callEvents.isEmpty()) {
                Text(
                    "O histórico antigo não preservou a decisão original. A classificação atual não comprova bloqueio antes do toque.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            callEvents.sortedWith(compareBy({ it.recordedAt }, { it.eventId })).forEach { event ->
                val detail = DecisionTrace.details(event)
                Text(
                    "Registrado em ${dateFormat.format(Date(event.recordedAt))} • ${DecisionTrace.eventLabel(event.eventType)}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                val description = listOfNotNull(
                    detail.outcome,
                    detail.score?.let { "Score $it / limite ${detail.threshold}" },
                    detail.disallowCall?.let {
                        if (it) "Pedido: bloquear" else if (detail.silenceCall == true) "Pedido: silenciar" else "Pedido: permitir"
                    },
                    detail.policy?.let { "Política ${it.normalAction} → ${it.chosenAction} · ${it.reason}" },
                    detail.latency?.elapsedLabel(),
                    detail.systemCallType?.let { "Android: tipo $it; motivo ${detail.systemBlockReason ?: "não informado"}" },
                    detail.durationSec?.let { "Duração: ${it}s" },
                    detail.observedCallTimestamp?.let { "Chamada observada em ${dateFormat.format(Date(it))}" },
                    detail.reason
                ).joinToString(" • ")
                Text(description, style = MaterialTheme.typography.bodySmall)
                if (detail.rules.isNotEmpty()) {
                    Text(
                        detail.rules.joinToString("; ") { "${it.rule_id} (${it.weight})" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "${event.source} • App ${event.appVersion} • Motor ${detail.engineRevision}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                text = "Classificação atual (pode incluir aprendizado posterior)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            val classification = provenance.currentClassification
            Text(
                text = if (classification != null) {
                    "Registrada pelo app ${classification.appVersion.label} em ${dateFormat.format(Date(classification.recordedAt))} (${DecisionTrace.eventLabel(classification.eventType)})."
                } else {
                    "Versão da classificação atual: desconhecida; nenhum evento correspondente foi preservado."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Versões presentes no diário: ${provenance.eventAppVersions.joinToString { it.label }.ifBlank { "nenhuma registrada" }}",
                style = MaterialTheme.typography.bodySmall
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
                        text = "• ${rule.rule_id}",
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
                "CONFIRMED_SPAM" -> "✓ SPAM CONFIRMADO PELO USUÁRIO (Prefixo $displayPrefix em Quarentena)"
                "FALSE_POSITIVE" -> "✓ FALSO POSITIVO (Prefixo $displayPrefix Liberado)"
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
                            "Spam confirmado! Prefixo $displayPrefix adicionado à quarentena.",
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
                            "Marcado como legítimo! Prefixo $displayPrefix removido da quarentena.",
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

@Composable
private fun CampaignBadge(attemptCount: Int, isSilenced: Boolean) {
    val (label, bgColor) = if (isSilenced) {
        "SILENCIADA ($attemptCount)" to Color(0xFFF59E0B)
    } else {
        "CAMPANHA ($attemptCount)" to Color(0xFF8B5CF6)
    }
    Surface(
        color = bgColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = bgColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun OutcomeBadge(outcome: String, score: Int, wasLearnedAfterRing: Boolean = false) {
    val (label, bgColor) = when {
        wasLearnedAfterRing -> "REJEITADA" to Color(0xFFEA580C)
        outcome == "Bloqueio registrado pelo Android" -> "BLOQUEIO REGISTRADO" to Color(0xFFDC2626)
        outcome.startsWith("Bloqueio solicitado") -> "SOLICITADO" to Color(0xFFD97706)
        outcome.startsWith("Legado") -> "LEGADO" to Color(0xFF64748B)
        outcome == "Atendida" -> "ATENDIDA" to Color(0xFF16A34A)
        outcome == "Chamada efetuada" -> "EFETUADA" to Color(0xFF64748B)
        else -> "AUDITADA" to Color(0xFF64748B)
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
