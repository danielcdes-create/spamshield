"""Read-only, reproducible checks for the supplied export. Outputs masked evidence only."""
import argparse
import collections
import datetime as dt
import hashlib
import json
from pathlib import Path

TZ = dt.timezone(dt.timedelta(hours=-3))


def analyze(path):
    content = path.read_bytes()
    rows = json.loads(content.decode("utf-8-sig"))
    parsed = {r["id"]: json.loads(r["triggeredRulesJson"]) for r in rows}

    def date(row):
        return dt.datetime.fromtimestamp(row["timestamp"] / 1000, TZ).isoformat()

    def counts(group, key):
        return dict(sorted(collections.Counter(r[key] for r in group).items()))

    def has(row, rule):
        return any(a["rule_id"] == rule for a in parsed[row["id"]])

    edge = [r for r in rows if r["deviceId"] == "android-edge-br"]
    today = [r for r in edge if date(r).startswith("2026-11-10")]
    pairs = [(a, b) for i, a in enumerate(rows) for b in rows[i + 1:]
             if a["normalizedNumber"] == b["normalizedNumber"]
             and abs(a["timestamp"] - b["timestamp"]) <= 15_000]
    daily = collections.defaultdict(list)
    for row in rows:
        daily[date(row)[:10]].append(row)
    # This is a coverage check, not proof that a signal was absent from the phone.
    uncorroborated_bursts = []
    for row in rows:
        if not has(row, "PREFIX_NEIGHBORHOOD_BURST"):
            continue
        observed_numbers = {r["normalizedNumber"] for r in rows
                            if r["prefix7"] == row["prefix7"]
                            and row["timestamp"] - 86_400_000 <= r["timestamp"] <= row["timestamp"]}
        if len(observed_numbers) < 2:
            uncorroborated_bursts.append(row["id"])
    return {
        "input": str(path.resolve()),
        "sha256": hashlib.sha256(content).hexdigest(),
        "timezone": "America/Sao_Paulo (UTC-03:00 in the observed period)",
        "row_count": len(rows),
        "range": [min(map(date, rows)), max(map(date, rows))],
        "grain": "One persisted telemetry row, not guaranteed one unique physical call",
        "duplicate_ids": len(rows) - len({r["id"] for r in rows}),
        "device_counts": counts(rows, "deviceId"),
        "outcomes": counts(rows, "outcome"),
        "edge_outcomes": counts(edge, "outcome"),
        "nov10_edge_outcomes": counts(today, "outcome"),
        "thresholds": counts(rows, "thresholdApplied"),
        "shadow_modes": counts(rows, "shadowModeActive"),
        "feedback": counts(rows, "userFeedback"),
        "stir": counts(rows, "stirShakenStatus"),
        "sync": counts(rows, "syncStatus"),
        "score_equation_failures": [r["id"] for r in rows
                                     if r["finalScore"] != max(0, sum(a["weight"] for a in parsed[r["id"]]))],
        "positive_duration_blocked": [r["id"] for r in rows
                                      if (r.get("callDurationSec") or 0) > 0 and r["outcome"] == "BLOCKED"],
        "system_calllog_rules": {
            "all": sum(has(r, "SYSTEM_CALLLOG_BLOCKED") for r in rows),
            "edge": sum(has(r, "SYSTEM_CALLLOG_BLOCKED") for r in edge),
            "blocked_edge": sum(has(r, "SYSTEM_CALLLOG_BLOCKED") and r["outcome"] == "BLOCKED" for r in edge),
        },
        "duplicate_pairs_within15s": [{"ids": [a["id"], b["id"]],
                                        "delta_ms": abs(a["timestamp"] - b["timestamp"]),
                                        "same_timestamp": a["timestamp"] == b["timestamp"]}
                                       for a, b in pairs],
        "duplicate_affected_rows": len({r["id"] for pair in pairs for r in pair}),
        "burst_rows_not_corroborated_by_two_distinct_numbers_in_prior24h_export": uncorroborated_bursts,
        "nov10_allowed": [{"id": r["id"], "time": date(r),
                           "masked_number": r["normalizedNumber"][:-4] + "****",
                           "score": r["finalScore"], "rules": parsed[r["id"]]}
                          for r in today if r["outcome"] == "ALLOWED_LOW_SCORE"],
        "unknown_055_rows": [{"id": r["id"], "time": date(r), "score": r["finalScore"]}
                             for r in rows if r["rawNumber"].startswith("055") and r["lineType"] == "UNKNOWN"],
        "daily": {d: {"rows": len(group), "devices": counts(group, "deviceId"),
                       "outcomes": counts(group, "outcome")}
                  for d, group in sorted(daily.items())},
        "limitations": [
            "No app/build/rules version, immutable arrival decision or screening acknowledgement.",
            "Export code reconciles and reprocesses stored rows before serializing.",
            "No quarantines snapshot, process start times, Android logcat or complete CallLog.",
            "Near rows are duplicate candidates; distinct physical calls cannot be ruled out from timing alone.",
            "Unrated allowed calls are not labelled false negatives.",
        ],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = analyze(args.input)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
