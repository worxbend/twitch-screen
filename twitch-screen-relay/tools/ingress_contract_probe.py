"""Synthetic HTTP parity checks against a caller-owned production relay.

No process, provider, desktop bus or board access. The caller owns launch/cleanup.
"""
import http.client
import json
from urllib.parse import urlsplit


def verify(base):
    authority = urlsplit(base)
    checks = []

    def exchange(path, body=None, method='GET', chunked=False):
        connection = http.client.HTTPConnection(authority.hostname, authority.port, timeout=5)
        headers = {'Content-Type': 'application/json'} if body is not None else {}
        encoded = None if body is None else body.encode('utf-8')
        if chunked:
            headers['Transfer-Encoding'] = 'chunked'
        try:
            connection.request(method, '/api/v1/' + path, body=encoded, headers=headers, encode_chunked=chunked)
            response = connection.getresponse()
            result = (response.status, dict(response.getheaders()), json.loads(response.read()))
            return result
        finally:
            connection.close()

    def history():
        return [value['seq'] for value in exchange('notifications?pageSize=80')[2]['notifications']]

    def clock():
        status, _, value = exchange('clock')
        assert status == 200
        return value

    def accepted(path, body, method, chunked=False):
        before = clock()
        status, headers, value = exchange(path, body, method, chunked)
        after = clock()
        assert status == 200, (path, status)
        normalized = {key.lower(): item for key, item in headers.items()}
        assert normalized['x-relay-epoch'] == before['relayEpoch'] == value['relayEpoch'] == after['relayEpoch']
        sampled = int(normalized['x-relay-monotonic-ms'])
        assert before['monotonicMs'] <= sampled <= after['monotonicMs']
        return value

    def cpu(host, freshness='1e3'):
        return '{"sourceId":"repair06","host":' + json.dumps(host) + ',"cpuPercent":12.34,"freshnessMs":' + freshness + '}'

    # Reproduces the old third-write refusal in the actual assembled Main.
    revisions = [accepted('cpu', cpu('synthetic-' + str(index)), 'PUT')['revision'] for index in range(3)]
    assert revisions == list(range(revisions[0], revisions[0] + 3))
    checks.append('actual Main accepts three consecutive CPU writes and returns matching exchange calibration')
    for label in ('😀' * 65, '😀' * 128, 'a' * 64 + '😀' * 64):
        value = accepted('cpu', cpu(label), 'PUT')
        assert value['data']['host'] == label
        assert exchange('cpu')[2]['data']['host'] == label
    before = exchange('cpu')[2]
    for body in (cpu('😀' * 129), cpu('a' * 129), cpu('synthetic').replace('synthetic', '\\ud800')):
        assert exchange('cpu', body, 'PUT')[0] == 400
    after = exchange('cpu')[2]
    assert (after['revision'], after['data']) == (before['revision'], before['data'])
    checks.append('actual Main preserves 128 Unicode scalar hosts and rejects overflow or isolated surrogate without replacement')

    card = '{"type":"info","title":"repair06 synthetic","body":""'
    for number in ('1000', '1e3', '1000.0', '10000e-1'):
        value = accepted('notifications', card + ',"ttlMs":' + number + ',"expiresInMs":6e4}', 'POST')
        assert (value['ttlMs'], value['expiresInMs']) == (1000, 60000)
        assert accepted('cpu', cpu('synthetic-integer', number), 'PUT')['data']['freshnessMs'] == 1000
    checks.append('actual POST and PUT accept exact integral decimal/exponent forms with matching calibration headers')
    before = exchange('cpu')[2]
    history_before = history()
    for number in ('1000.1', 'true', '"1000"', 'null', '9223372036854775808', '1e999999'):
        assert exchange('cpu', cpu('synthetic-invalid', number), 'PUT')[0] == 400
        assert exchange('notifications', card + ',"ttlMs":' + number + '}', 'POST')[0] == 400
    after = exchange('cpu')[2]
    assert (after['revision'], after['data']) == (before['revision'], before['data'])
    assert history() == history_before
    checks.append('actual invalid integer writes reject before CPU or notification mutation')

    escaped = json.dumps({'type': 'info', 'title': 'é' * 4096, 'body': 'é' * 4096})
    assert 16384 < len(escaped.encode()) < 65536
    exact = card + '}'
    exact += ' ' * (65536 - len(exact.encode()))
    for body in (escaped, exact):
        for chunked in (False, True):
            accepted('notifications', body, 'POST', chunked)
    history_before = history()
    for chunked in (False, True):
        assert exchange('notifications', exact + ' ', 'POST', chunked)[0] == 413
    assert history() == history_before
    checks.append('actual Main accepts valid escaped and exact 64 KiB declared/chunked bodies; overflow leaves history unchanged')
    return checks
