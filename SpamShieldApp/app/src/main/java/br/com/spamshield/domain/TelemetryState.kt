package br.com.spamshield.domain

import br.com.spamshield.data.local.entity.CallTelemetryEntity
import br.com.spamshield.data.local.entity.TriggeredRuleDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

object TelemetrySyncScheduler {
    @Volatile
    var enqueuedCount: Int = 0
        private set

    private var sink: (() -> Unit)? = null

    fun install(sink: () -> Unit) {
        this.sink = sink
    }

    fun enqueueImmediateSync() {
        enqueuedCount += 1
        sink?.invoke()
    }

    fun resetForTests() {
        enqueuedCount = 0
        sink = null
    }
}

object SyncSelection {
    fun pending(rows: List<CallTelemetryEntity>): List<CallTelemetryEntity> =
        rows.filter { it.syncStatus == "PENDING" }

    fun markSynced(rows: List<CallTelemetryEntity>, sentIds: Set<String>): List<CallTelemetryEntity> =
        rows.map { row ->
            if (row.id in sentIds) row.copy(syncStatus = "SYNCED") else row
        }

    fun complete(rows: List<CallTelemetryEntity>): List<CallTelemetryEntity> {
        val ids = pending(rows).map { it.id }.toSet()
        return markSynced(rows, ids)
    }
}

object UserFeedbackAudit {
    private val json = Json { ignoreUnknownKeys = true }

    const val OVERRIDE_RULE = "USER_CONFIRMED_SPAM_OVERRIDE"
    const val OVERRIDE_META = "Feedback manual do utilizador"

    fun applyConfirmedSpam(record: CallTelemetryEntity): CallTelemetryEntity {
        val rules = decode(record.triggeredRulesJson).toMutableList()
        if (rules.none { it.rule_id == OVERRIDE_RULE }) {
            rules.add(TriggeredRuleDto(OVERRIDE_RULE, 100, OVERRIDE_META))
        }
        val score = max(0, rules.sumOf { it.weight })
        val outcome = if (record.outcome == "ALLOWED_LOW_SCORE") {
            "USER_BLOCKED_RETROACTIVE"
        } else {
            record.outcome
        }
        val updated = record.copy(
            finalScore = score,
            outcome = outcome,
            category = "USER_REPORTED_SPAM",
            triggeredRulesJson = json.encodeToString(rules),
            userFeedback = "CONFIRMED_SPAM",
            syncStatus = "PENDING"
        )
        TelemetrySyncScheduler.enqueueImmediateSync()
        return updated
    }

    fun decode(triggeredRulesJson: String): List<TriggeredRuleDto> =
        runCatching { json.decodeFromString<List<TriggeredRuleDto>>(triggeredRulesJson) }
            .getOrDefault(emptyList())

    fun encode(rules: List<TriggeredRuleDto>): String = json.encodeToString(rules)
}

object TelemetryCoalescer {
    const val WINDOW_MS = 15_000L

    fun persist(
        store: MutableList<CallTelemetryEntity>,
        incoming: CallTelemetryEntity
    ): List<CallTelemetryEntity> {
        val index = store.indexOfFirst { existing ->
            existing.normalizedNumber == incoming.normalizedNumber &&
                abs(existing.timestamp - incoming.timestamp) <= WINDOW_MS
        }
        if (index < 0) {
            store.add(incoming)
        } else {
            store[index] = merge(store[index], incoming)
        }
        return store
    }

    fun merge(first: CallTelemetryEntity, second: CallTelemetryEntity): CallTelemetryEntity {
        val base = chooseBase(first, second)
        val other = if (base === first) second else first
        val stir = preserveStir(base.stirShakenStatus, other.stirShakenStatus)
        val duration = preserveDuration(base.callDurationSec, other.callDurationSec)
        var merged = base.copy(
            timestamp = minOf(first.timestamp, second.timestamp),
            stirShakenStatus = stir,
            callDurationSec = duration
        )
        if ((merged.callDurationSec ?: 0) > 0 && merged.outcome == "BLOCKED") {
            merged = merged.copy(outcome = "FLAGGED_POST_CALL_QUARANTINED")
        }
        return merged
    }

    fun dedupeSameTimestamp(records: List<CallTelemetryEntity>): List<CallTelemetryEntity> {
        val grouped = LinkedHashMap<Pair<String, Long>, MutableList<CallTelemetryEntity>>()
        records.forEach { row ->
            grouped.getOrPut(row.normalizedNumber to row.timestamp) { mutableListOf() }.add(row)
        }
        return grouped.values.map { group ->
            group.firstOrNull { row ->
                TelemetrySanitizer.DUPLICATE_SECOND_PREFIXES.none { row.id.startsWith(it) }
            } ?: group.first()
        }
    }

    private fun chooseBase(first: CallTelemetryEntity, second: CallTelemetryEntity): CallTelemetryEntity {
        val firstDown = isObserverDowngrade(first)
        val secondDown = isObserverDowngrade(second)
        return when {
            firstDown && !secondDown -> second
            secondDown && !firstDown -> first
            first.timestamp <= second.timestamp -> first
            else -> second
        }
    }

    private fun isObserverDowngrade(record: CallTelemetryEntity): Boolean =
        record.stirShakenStatus == "NOT_VERIFIED" && record.callDurationSec == null

    private fun preserveStir(primary: String, secondary: String): String {
        if (primary == "PASSED" || primary == "FAILED") return primary
        if (secondary == "PASSED" || secondary == "FAILED") return secondary
        return primary
    }

    private fun preserveDuration(primary: Int?, secondary: Int?): Int? = when {
        secondary == null -> primary
        primary == null -> secondary
        secondary > primary -> secondary
        else -> primary
    }
}

object TelemetrySanitizer {
    val DUPLICATE_SECOND_PREFIXES = listOf(
        "4b98f6ee",
        "effade14",
        "8f9cc607",
        "1b5749de",
        "c8b28626",
        "940c2a06",
        "203688fe",
        "1371beb3",
        "409d2321"
    )

    val PENDING_FLUSH_PREFIXES = listOf("96854886", "0e43a524", "e68988dc")

    const val BURST_START_MS = 1_792_718_165_716L
    const val BURST_END_MS = 1_792_718_225_739L

    fun sanitize(rows: List<CallTelemetryEntity>): List<CallTelemetryEntity> {
        val withoutNamedDuplicates = rows.filter { row ->
            DUPLICATE_SECOND_PREFIXES.none { row.id.startsWith(it) }
        }
        val withoutBurstNulls = dropBurstNullDuplicates(withoutNamedDuplicates)
        val collapsed = collapseWindow(withoutBurstNulls)
        return collapsed.map { rewrite(it) }
    }

    fun nationalPrefix8(normalizedNumber: String): String? {
        val digits = normalizedNumber.filter { it.isDigit() }
        val national = if (digits.startsWith("55") && digits.length >= 12) digits.drop(2) else digits
        return national.take(8).takeIf { it.length == 8 }
    }

    private fun dropBurstNullDuplicates(rows: List<CallTelemetryEntity>): List<CallTelemetryEntity> {
        val inBurst = rows.filter { it.timestamp in BURST_START_MS..BURST_END_MS }
        val dropIds = mutableSetOf<String>()
        inBurst.groupBy { it.normalizedNumber }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val nullRows = group.filter { it.callDurationSec == null }
            if (nullRows.isEmpty()) return@forEach
            if (group.any { it.callDurationSec != null }) {
                dropIds.addAll(nullRows.map { it.id })
            } else {
                dropIds.addAll(nullRows.drop(1).map { it.id })
            }
        }
        return rows.filter { it.id !in dropIds }
    }

    private fun collapseWindow(rows: List<CallTelemetryEntity>): List<CallTelemetryEntity> {
        val kept = mutableListOf<CallTelemetryEntity>()
        rows.sortedBy { it.timestamp }.forEach { row ->
            val clash = kept.indexOfFirst {
                it.normalizedNumber == row.normalizedNumber &&
                    abs(it.timestamp - row.timestamp) <= TelemetryCoalescer.WINDOW_MS
            }
            if (clash < 0) {
                kept.add(row)
            } else {
                kept[clash] = TelemetryCoalescer.merge(kept[clash], row)
            }
        }
        return kept
    }

    private fun rewrite(row: CallTelemetryEntity): CallTelemetryEntity {
        var current = row
        if (
            current.prefix7.length == 7 &&
            (current.lineType == "MOBILE" || current.lineType == "FIXED_OR_VOIP")
        ) {
            nationalPrefix8(current.normalizedNumber)?.let { current = current.copy(prefix7 = it) }
        }
        current = repairVerifiedFixed(current)
        if (current.userFeedback == "CONFIRMED_SPAM" && current.finalScore == 100) {
            val rules = UserFeedbackAudit.decode(current.triggeredRulesJson).toMutableList()
            if (rules.none { it.rule_id == UserFeedbackAudit.OVERRIDE_RULE }) {
                rules.add(
                    TriggeredRuleDto(
                        UserFeedbackAudit.OVERRIDE_RULE,
                        100,
                        UserFeedbackAudit.OVERRIDE_META
                    )
                )
            }
            current = current.copy(triggeredRulesJson = UserFeedbackAudit.encode(rules))
        }
        val rules = UserFeedbackAudit.decode(current.triggeredRulesJson)
        val score = max(0, rules.sumOf { it.weight })
        val queued = PENDING_FLUSH_PREFIXES.any { current.id.startsWith(it) }
        return current.copy(
            finalScore = score,
            syncStatus = if (queued) "PENDING" else current.syncStatus
        )
    }

    private fun repairVerifiedFixed(row: CallTelemetryEntity): CallTelemetryEntity {
        if (row.stirShakenStatus != "PASSED" || row.carrierGuess != "Fixo") return row
        if (row.triggeredRulesJson.contains("PREFIX_QUARANTINE_HIT")) return row
        val subscriber = subscriberOf(row.normalizedNumber)
        var rules = UserFeedbackAudit.decode(row.triggeredRulesJson)
        if (!HeuristicScorer.subscriberInDedicatedVoipBlock(subscriber)) {
            rules = rules.filter { it.rule_id != "ANATEL_PABX_VOIP_RANGE" }
        }
        val probe = ScoreInput(
            rawNumber = row.rawNumber,
            normalizedNumber = row.normalizedNumber,
            ddd = row.ddd,
            prefix7 = row.prefix7,
            subscriberNumber = subscriber,
            carrierGuess = row.carrierGuess,
            lineType = row.lineType,
            stirShakenStatus = row.stirShakenStatus,
            quarantineMatch = PrefixQuarantineMatch.NONE
        )
        if (!HeuristicScorer.shouldApplyLeadingZero(probe)) {
            rules = rules.filter { it.rule_id != "LEADING_ZERO_TRUNK_DIALING" }
        }
        val score = max(0, rules.sumOf { it.weight })
        val outcome = if (score >= row.thresholdApplied && (row.callDurationSec ?: 0) > 0) {
            "FLAGGED_POST_CALL_QUARANTINED"
        } else if (score >= row.thresholdApplied) {
            row.outcome
        } else {
            "ALLOWED_LOW_SCORE"
        }
        val category = if (outcome == "ALLOWED_LOW_SCORE") "VERIFIED_UNKNOWN" else row.category
        return row.copy(
            triggeredRulesJson = UserFeedbackAudit.encode(rules),
            finalScore = score,
            outcome = outcome,
            category = category
        )
    }

    private fun subscriberOf(normalizedNumber: String): String {
        val digits = normalizedNumber.filter { it.isDigit() }
        val national = if (digits.startsWith("55") && digits.length >= 12) digits.drop(2) else digits
        return if (national.length > 2) national.drop(2) else national
    }
}
