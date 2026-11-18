"""Read-only audit of an exported journal; outputs IDs, never full phone numbers.

Usage: python audit/2026-11-17/analyze_telemetry.py INPUT --output OUTPUT
One row is one persisted entity, not necessarily one physical incoming call.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
from pathlib import Path

TZ = dt.timezone(dt.timedelta(hours=-3))


def date(ms):
    return dt.datetime.fromtimestamp(ms / 1000, TZ).isoformat()


def events(row, kind):
    return sorted((e for e in row.get('decisionEvents', []) if e['event_type'] == kind),
                  key=lambda e: e['recorded_at'])


def latest(row, kind):
    seq = events(row, kind)
    return seq[-1]['details'] if seq else {}


def evidence(row):
    response = latest(row, 'SCREENING_RESPONSE_SUBMITTED')
    obs = latest(row, 'CALL_LOG_OBSERVATION')
    requested = response.get('disallowCall') is True
    observed = (requested and obs.get('systemCallType') == 6
                and obs.get('systemBlockReason') == 1
                and obs.get('systemScreeningAppIsSpamShield') is not False)
    if observed:
        return 'BLOCK_REQUEST_AND_ANDROID_EVIDENCE'
    if requested:
        return 'BLOCK_REQUEST_WITHOUT_ANDROID_CONFIRMATION'
    if response:
        return 'ALLOW_RESPONSE'
    return 'NO_SCREENING_RESPONSE'


def summary(rows):
    screened = [r for r in rows if events(r, 'SCREENING_DECISION')]
    return {
        'rows': len(rows),
        'rows_with_arrival_decision': len(screened),
        'arrival_outcomes': dict(collections.Counter(latest(r, 'SCREENING_DECISION').get('outcome') for r in screened)),
        'current_outcomes': dict(collections.Counter(r['outcome'] for r in rows)),
        'evidence': dict(collections.Counter(evidence(r) for r in rows)),
        'latest_call_log_type': dict(collections.Counter(str(latest(r, 'CALL_LOG_OBSERVATION').get('systemCallType')) for r in rows)),
    }


def analyze(path):
    content = path.read_bytes()
    rows = json.loads(content.decode('utf-8-sig'))
    all_events = [(r, e) for r in rows for e in r.get('decisionEvents', [])]
    screened = [r for r in rows if events(r, 'SCREENING_DECISION')]
    by_day = collections.defaultdict(list)
    by_version = collections.defaultdict(list)
    for row in screened:
        by_day[date(row['timestamp'])[:10]].append(row)
        by_version[events(row, 'SCREENING_DECISION')[0]['app_version']].append(row)
    submission_ms = [e['details']['elapsedMs'] for _, e in all_events
                     if e['event_type'] == 'SCREENING_RESPONSE_SUBMITTED'
                     and e['details'].get('elapsedMs') is not None]
    def violations(seq):
        return [r['id'] for r in seq if r['finalScore'] != max(0, sum(
            t['weight'] for t in json.loads(r['triggeredRulesJson'])))]
    event_scores = [{'call_id': r['id'], 'event_id': e['event_id']} for r, e in all_events
                    if e['details'].get('score') is not None
                    and e['details']['score'] != max(0, sum(t['weight'] for t in e['details'].get('rules', [])))]
    pairs = []
    chronological = sorted(rows, key=lambda r: r['timestamp'])
    for i, row in enumerate(chronological):
        for other in chronological[i+1:]:
            delta = other['timestamp'] - row['timestamp']
            if delta > 15_000:
                break
            if row['normalizedNumber'] == other['normalizedNumber']:
                pairs.append({'ids': [row['id'], other['id']], 'delta_ms': delta,
                              'both_have_arrival': bool(events(row, 'SCREENING_DECISION') and events(other, 'SCREENING_DECISION'))})
    changed = []
    allowed_rejected = []
    for row in screened:
        arrival = latest(row, 'SCREENING_DECISION')
        obs = latest(row, 'CALL_LOG_OBSERVATION')
        if arrival.get('outcome') != row['outcome'] or arrival.get('score') != row['finalScore']:
            changed.append({'id': row['id'], 'time': date(row['timestamp']),
                            'arrival': [arrival.get('outcome'), arrival.get('score')],
                            'current': [row['outcome'], row['finalScore']],
                            'call_type': obs.get('systemCallType')})
        if not latest(row, 'SCREENING_RESPONSE_SUBMITTED').get('disallowCall') and obs.get('systemCallType') == 5:
            allowed_rejected.append({'id': row['id'], 'time': date(row['timestamp']),
                                     'arrival_score': arrival.get('score'), 'feedback': row['userFeedback'],
                                     'arrival_rules': arrival.get('rules'), 'current_outcome': row['outcome']})
    observations_by_timestamp = collections.defaultdict(list)
    for row, event in all_events:
        if event['event_type'] == 'CALL_LOG_OBSERVATION':
            details = event['details']
            key = row['normalizedNumber'], details.get('observedCallTimestamp')
            observations_by_timestamp[key].append(row['id'])
    shared_observations = [sorted(set(ids)) for ids in observations_by_timestamp.values() if len(set(ids)) > 1]
    event_id_counts = collections.Counter(e['event_id'] for _, e in all_events)
    outgoing = [{'id': row['id'], 'time': date(row['timestamp']),
                 'current_outcome': row['outcome'], 'score': row['finalScore']}
                for row in rows if latest(row, 'CALL_LOG_OBSERVATION').get('systemCallType') == 2]
    return {
        'source': str(path.resolve()), 'source_sha256': hashlib.sha256(content).hexdigest(),
        'timezone': 'America/Sao_Paulo (UTC-03 in observed period)',
        'grain': 'One persisted entity, not guaranteed one unique incoming physical call',
        'range': [date(min(r['timestamp'] for r in rows)), date(max(r['timestamp'] for r in rows))],
        'summary': summary(rows),
        'device_counts': dict(collections.Counter(r['deviceId'] for r in rows)),
        'feedback_counts': dict(collections.Counter(r['userFeedback'] for r in rows)),
        'sync_counts': dict(collections.Counter(r['syncStatus'] for r in rows)),
        'event_count': len(all_events),
        'event_type_counts': dict(collections.Counter(e['event_type'] for _, e in all_events)),
        'duplicate_row_ids': [k for k, v in collections.Counter(r['id'] for r in rows).items() if v > 1],
        'duplicate_event_ids': [k for k, v in event_id_counts.items() if v > 1],
        'row_score_equation_failures': violations(rows), 'event_score_equation_failures': event_scores,
        'screened_by_day': {k: summary(v) for k, v in sorted(by_day.items())},
        'screened_by_app_version': {k: summary(v) for k, v in sorted(by_version.items())},
        'response_elapsed_ms': {'count': len(submission_ms), 'min': min(submission_ms),
                                'median': sorted(submission_ms)[len(submission_ms)//2],
                                'max': max(submission_ms), 'at_least_5000': sum(t >= 5000 for t in submission_ms)},
        'arrival_cache_not_ready_ids': [r['id'] for r in screened if latest(r, 'SCREENING_DECISION').get('quarantineCacheReady') is False],
        'screening_errors': [{'id': r['id'], 'type': e['event_type']} for r, e in all_events
                             if e['event_type'] in ('SCREENING_ERROR', 'SCREENING_RESPONSE_FAILED')],
        'arrival_current_differences': changed,
        'allow_response_then_dialer_rejected': allowed_rejected,
        'same_number_rows_within_15s': pairs,
        'call_log_observation_assigned_to_multiple_rows': shared_observations,
        'outgoing_rows': outgoing,
        'confirmed_spam_without_feedback_event': [r['id'] for r in rows
                                                 if r['userFeedback'] == 'CONFIRMED_SPAM' and not events(r, 'USER_FEEDBACK')],
        'limitations': [
            'An allowed unrated call is not proof of spam or a false negative.',
            'A block request plus Android evidence supports attribution; it does not measure audible ringing.',
            'No complete independent CallLog, quarantine mutation journal or installed-process logs supplied.',
            'Near-time entities and shared observations may inflate call counts; physical-call identity is not independently available.',
            'Current row outcomes can change after reconciliation and reprocessing; arrival events are evaluated separately.',
        ],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    findings = analyze(args.input)
    args.output.write_text(json.dumps(findings, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({k: findings[k] for k in ('summary', 'screened_by_app_version', 'response_elapsed_ms')}, indent=2))
