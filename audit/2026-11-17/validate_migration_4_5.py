"""Run the migration's actual SQL on an isolated SQLite v4 fixture.

This verifies SQLite statements and the feedback replay/preservation contract, not
Android Room's migration runner. It never reads .env, telemetry exports or a server.
"""
from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DB_SOURCE = ROOT / "SpamShieldApp/app/src/main/java/br/com/spamshield/data/local/AppDatabase.kt"
ENTITIES = ROOT / "SpamShieldApp/app/src/main/java/br/com/spamshield/data/local/entity/Entities.kt"
DTO_SOURCE = ROOT / "SpamShieldApp/app/src/main/java/br/com/spamshield/data/remote/SpamShieldApi.kt"
TABLES = {
    "CallTelemetryEntity": ("call_telemetry", "id"),
    "CallDecisionEventEntity": ("call_decision_events", "eventId"),
    "ActiveRuleEntity": ("active_rules", "ruleId"),
    "PrefixQuarantineEntity": ("prefix_quarantine", "prefix7"),
    "OutboundGraceEntity": ("outbound_grace", "normalizedNumber"),
    "AnatelPrefixEntity": ("anatel_numbering_plan", "prefix7"),
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def validate():
    source = DB_SOURCE.read_text(encoding="utf-8")
    block = source.split("val MIGRATION_4_5 =", 1)[1].split("val MIGRATION_3_4 =", 1)[0]
    statements = []
    for match in re.finditer(r'db\.execSQL\((?:"""([\s\S]*?)"""|"((?:\\.|[^"\\])*)")', block):
        statements.append(match[1] if match[1] is not None else json.loads('"' + match[2] + '"'))
    ddl = [sql for sql in statements if sql.lstrip().startswith("CREATE ")]
    inserts = [sql for sql in statements if sql.lstrip().startswith("INSERT ")]
    queries = [json.loads('"' + text + '"') for text in re.findall(r'db\.query\("((?:\\.|[^"\\])*)"', block)]
    assert len(ddl) == 3 and len(inserts) == 1 and len(queries) == 2
    assert all("DROP " not in sql and "DELETE " not in sql for sql in statements)
    mutation_prefix = re.search(r'val mutationId = "([^"$]+)\$id"', block)[1]
    timestamp_sources = re.findall(r'recordedAtSource = "([^"$]+)"', block)
    assert timestamp_sources == ["LEGACY_CALL_TIME_LOWER_BOUND", "USER_FEEDBACK_EVENT"]
    assert "FeedbackPatchRequestDto(feedback, mutationId, recordedAt, recordedAtSource)" in block
    dto = DTO_SOURCE.read_text(encoding="utf-8").split("data class FeedbackPatchRequestDto(", 1)[1].split(")", 1)[0]
    assert re.findall(r"val (\w+):", dto) == ["user_feedback", "mutation_id", "recorded_at", "recorded_at_source"]
    entities = ENTITIES.read_text(encoding="utf-8")
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA user_version = 4")
    schemas = {}
    for entity, (table, primary_key) in TABLES.items():
        body = entities.split(f"data class {entity}(", 1)[1].split("\n)", 1)[0]
        fields = re.findall(r"val (\w+): (String|Long|Int|Double|Boolean)(\?)?", body)
        assert fields, entity
        schemas[table] = fields
        types = {"String": "TEXT", "Long": "INTEGER", "Int": "INTEGER", "Boolean": "INTEGER", "Double": "REAL"}
        columns = [f'"{name}" {types[kind]}' + ("" if nullable else " NOT NULL") +
                   (" PRIMARY KEY" if name == primary_key else "") for name, kind, nullable in fields]
        db.execute(f'CREATE TABLE "{table}" ({", ".join(columns)})')

    def insert(table, **values):
        fields = schemas[table]
        row = {name: None if nullable else "" if kind == "String" else 0 for name, kind, nullable in fields}
        row.update(values)
        columns = ", ".join(f'"{name}"' for name in row)
        db.execute(f'INSERT INTO "{table}" ({columns}) VALUES ({", ".join("?" for _ in row)})', tuple(row.values()))

    insert("call_telemetry", id="legacy-unrated", timestamp=1000, userFeedback="UNRATED", outcome="BLOCKED", finalScore=95)
    insert("call_telemetry", id="offline-spam", timestamp=2000, userFeedback="CONFIRMED_SPAM", outcome="USER_BLOCKED_RETROACTIVE", finalScore=140)
    insert("call_telemetry", id="corrected-legitimate", timestamp=3000, userFeedback="FALSE_POSITIVE", outcome="ALLOWED_CONTACT", finalScore=0)
    insert("call_decision_events", eventId="original-arrival", callId="corrected-legitimate", recordedAt=3010,
           eventType="SCREENING_DECISION", source="CALL_SCREENING_SERVICE", appVersion="1.3.1 (15)",
           detailsJson='{"score":40,"outcome":"ALLOWED_LOW_SCORE"}')
    insert("call_decision_events", eventId="feedback-old", callId="corrected-legitimate", recordedAt=4000,
           eventType="USER_FEEDBACK", appVersion="1.3.1 (15)", detailsJson='{"reason":"CONFIRMED_SPAM"}')
    insert("call_decision_events", eventId="feedback-new", callId="corrected-legitimate", recordedAt=5000,
           eventType="USER_FEEDBACK", appVersion="1.3.2 (16)", detailsJson='{"reason":"FALSE_POSITIVE"}')
    insert("active_rules", ruleId="disabled-rule", enabled=0, weight=85)
    insert("prefix_quarantine", prefix7="31990000", expiresAt=9_999_999, reason="DOWNLOADED_SNAPSHOT")
    insert("prefix_quarantine", prefix7="31991111", expiresAt=1, reason="EXPIRED_SNAPSHOT")
    insert("outbound_grace", normalizedNumber="fixture-outbound", prefix6="319900", expiresAt=9_999_999)
    insert("anatel_numbering_plan", prefix7="3199000", carrier="fixture-carrier", lineType="MOBILE")

    def snapshots():
        return {table: sorted(db.execute(f'SELECT * FROM "{table}"').fetchall()) for table, _ in TABLES.values()}

    original = snapshots()

    def migrate_sql_and_replay():
        for sql in ddl:
            db.execute(sql)
        for call_id, feedback, recorded_at in db.execute(queries[0]).fetchall():
            recorded_at_source = timestamp_sources[0]
            event = db.execute(queries[1], (call_id,)).fetchone()
            if event is not None:
                recorded_at = event[0]
                recorded_at_source = timestamp_sources[1]
            mutation_id = mutation_prefix + call_id
            payload = json.dumps({"user_feedback": feedback, "mutation_id": mutation_id, "recorded_at": recorded_at,
                                  "recorded_at_source": recorded_at_source}, separators=(",", ":"))
            db.execute(inserts[0], (mutation_id, "FEEDBACK", call_id, payload, recorded_at))
        db.execute("PRAGMA user_version = 5")

    migrate_sql_and_replay()
    first = db.execute("SELECT * FROM sync_mutations ORDER BY mutationId").fetchall()
    migrate_sql_and_replay()
    second = db.execute("SELECT * FROM sync_mutations ORDER BY mutationId").fetchall()
    assert original == snapshots(), "Migration changed original v4 tables"
    assert first == second and len(second) == 2, "Replay must be idempotent and enqueue rated calls only"
    by_call = {row[2]: json.loads(row[3]) for row in second}
    assert by_call["offline-spam"]["recorded_at"] == 2000
    assert by_call["offline-spam"]["recorded_at_source"] == "LEGACY_CALL_TIME_LOWER_BOUND"
    assert by_call["corrected-legitimate"]["recorded_at"] == 5000
    assert by_call["corrected-legitimate"]["recorded_at_source"] == "USER_FEEDBACK_EVENT"
    assert by_call["corrected-legitimate"]["user_feedback"] == "FALSE_POSITIVE"
    assert db.execute("SELECT recordedAt, appVersion FROM call_decision_events WHERE eventId = 'original-arrival'").fetchone() == (3010, "1.3.1 (15)")
    assert db.execute("SELECT COUNT(*) FROM call_decision_events WHERE callId = 'offline-spam'").fetchone()[0] == 0
    assert {row[2]: row[4] for row in second} == {"offline-spam": 2000, "corrected-legitimate": 5000}
    assert db.execute("SELECT COUNT(*) FROM remote_quarantine_prefixes").fetchone()[0] == 0
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    indexes = [row[1] for row in db.execute("PRAGMA index_list(sync_mutations)")]
    assert "index_sync_mutations_createdAt" in indexes
    return {
        "result": "passed", "sqlite_version": sqlite3.sqlite_version,
        "source": str(DB_SOURCE.relative_to(ROOT)), "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
        "migration": "4->5", "sql_extracted_from_source": statements,
        "queries_extracted_from_source": queries,
        "preserved_v4_tables": {name: {"rows": len(rows), "sha256_before_and_after": digest(rows)} for name, rows in original.items()},
        "queued_feedback_mutations": len(second), "unrated_calls_queued": 0,
        "remote_snapshots_echoed_as_local_mutations": 0, "second_run_identical": first == second,
        "feedback_uses_latest_user_event_timestamp": True,
        "known_feedback_time_source": by_call["corrected-legitimate"]["recorded_at_source"],
        "legacy_feedback_time_source": by_call["offline-spam"]["recorded_at_source"],
        "legacy_call_time_is_only_an_ordering_lower_bound": True,
        "legacy_feedback_time_is_not_presented_as_observed": True,
        "original_call_version_and_arrival_event_unchanged": True,
        "migration_fabricated_original_or_feedback_events": 0,
        "integrity_check": "ok", "network_access": False,
        "limitation": "Actual SQLite SQL executed; the Kotlin feedback loop was reproduced against synthetic fixtures. Android Room migration runner was not executed."
    }


if __name__ == "__main__":
    result = validate()
    output = Path(__file__).with_name("migration-4-5-verification.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": result["result"], "migration": result["migration"], "queued_feedback_mutations": result["queued_feedback_mutations"], "output": str(output)}))
