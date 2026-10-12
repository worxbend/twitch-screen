"""Validate original-card lifecycle evidence without requiring uncoalesced reports."""


def verify_report_phases(records):
    """Permit skipped phases and reconnects, but never reverse or revive a card."""
    phases = {}
    finished = set()
    active = None
    for row in records:
        if row['event'] != 'device_report_written':
            continue
        phase = row['phase']
        assert phase in (0, 1, 2, 3), 'Unknown active phase'
        identity = (row['activeEpoch'], row['activeSeq']) if phase else None
        if active is not None and identity != active:
            finished.add(active)
        if identity is not None:
            assert identity not in finished, 'Completed lifecycle identity reappeared'
            assert phase >= phases.get(identity, 0), 'Active phase regressed for original card identity'
            phases[identity] = phase
        active = identity
    return len(phases)


def verify_callbacks(records, expected_holds, *, hold_upper_ms=None):
    """Check actual LVGL callbacks independently of coalesced device reports.

    Times share the runtime peer's monotonic device origin. A report may be
    encoded before its bytes leave the socket, so compare observation time,
    not JSON output order, with the callback boundaries. An explicitly scoped
    process pause may increase one original card's hold upper bound only; it
    never relaxes hold minima, exit bounds, report phases or cross-card ordering.
    """
    hold_upper_ms = {} if hold_upper_ms is None else hold_upper_ms
    cards = {}
    order = []
    for row in records:
        if row['event'] == 'started':
            identity = (row['epoch'], row['seq'])
            assert identity not in cards, 'Duplicate lifecycle start'
            if order:
                previous = cards[order[-1]]
                assert len(previous) == 4 and row['atMs'] >= previous[-1]['atMs'], 'Overlapping callback lifecycles'
            cards[identity] = [row]
            order.append(identity)
        elif row['event'] == 'card_lifecycle':
            identity = (row['epoch'], row['seq'])
            assert order and identity == order[-1], 'Callback lifecycle identity changed'
            events = cards[identity]
            assert len(events) <= 3, 'Repeated callback lifecycle completion'
            assert row['phase'] == (2, 3, 0)[len(events)-1], 'Callback lifecycle phase regressed or repeated'
            assert row['atMs'] >= events[-1]['atMs'], 'Callback lifecycle time regressed'
            events.append(row)
    assert len(cards) == len(expected_holds), 'Missing callback lifecycle evidence'
    assert set(hold_upper_ms) <= set(cards), 'Hold exception has no original lifecycle'
    for identity, hold_ms in zip(order, expected_holds):
        events = cards[identity]
        assert len(events) == 4, 'Incomplete callback lifecycle'
        start, hold, exit_card, complete = events
        assert start['holdMs'] == hold_ms, 'Original lifecycle hold changed'
        upper_ms = hold_upper_ms.get(identity, hold_ms + 2000)
        assert upper_ms >= hold_ms + 2000, 'Hold exception shortened ordinary bound'
        assert hold_ms <= exit_card['atMs']-hold['atMs'] < upper_ms, 'Original lifecycle hold did not finish'
        assert 280 <= complete['atMs']-exit_card['atMs'] < 2280, 'Original lifecycle exit did not finish'
        for row in records:
            if row['event'] != 'device_report_written' or not row['phase']:
                continue
            if (row['activeEpoch'], row['activeSeq']) != identity:
                continue
            lower, upper = {1:(start,hold), 2:(hold,exit_card), 3:(exit_card,complete)}[row['phase']]
            assert lower['atMs'] <= row['observedDeviceMs'] <= upper['atMs'], 'Reported phase contradicts callback lifecycle'
    return dict(timestampedLifecyclesChecked=len(cards),originalHoldDurationsChecked=True)
