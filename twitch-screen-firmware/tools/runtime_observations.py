"""Validate native observation identities without asserting physical rendering."""
import re

ZERO = '0000000000000000'


def session_key(row):
    """Connection IDs are unique only within one relay epoch."""
    return row['epoch'], row['connection']


def verify_application_sessions(records):
    """Require each connected report/view/numeric row to carry a ready session.

    Hidden pre-SYNC_END views may precede their ready marker. Disconnected views
    retain no carrying connection and cannot establish numeric application proof.
    Numeric observations must immediately follow their matching view; a matching
    foreign pair is not authority. Session progression cannot return to an older
    carrying session even when a restart reuses its numeric connection ID.
    """
    ready = [session_key(row) for row in records if row['event'] == 'desk_ready']
    assert ready and len(ready) == len(set(ready)), 'Missing or duplicate ready session identity'
    for key in ready:
        assert all(isinstance(value, str) and re.fullmatch('[0-9a-f]{16}', value)
                   and value != ZERO for value in key), 'Invalid ready epoch/connection'
    rank = {key: index for index, key in enumerate(ready)}
    latest = -1
    for position, row in enumerate(records):
        event = row['event']
        if event == 'application_telemetry':
            assert position and records[position - 1]['event'] == 'application_view', 'Detached numeric observation'
        if event not in ('desk_ready', 'device_report_written', 'application_view', 'application_telemetry'):
            continue
        key = session_key(row)
        if event in ('application_view', 'application_telemetry') and key[1] == ZERO:
            assert key[0] == ZERO or key[0] in {epoch for epoch, _ in ready}, 'Disconnected view has a foreign epoch'
            if event == 'application_view':
                assert (row['baseScreen'], row['reason']) == (1, 1)
                assert not row['cpuVisible'] and not row['weatherVisible']
        else:
            assert key in rank, 'Observation belongs to an unknown epoch/connection'
            assert rank[key] >= latest, 'Observation returned to an obsolete carrying session'
            latest = rank[key]
        if event == 'application_view':
            assert position + 1 < len(records), 'Missing adjacent numeric observation'
            numeric = records[position + 1]
            assert numeric['event'] == 'application_telemetry' and all(
                numeric[field] == row[field] for field in ('epoch', 'connection', 'displayRevision', 'cpuRevision')
            ), 'View/numeric observation identity differs'
    return set(ready)
