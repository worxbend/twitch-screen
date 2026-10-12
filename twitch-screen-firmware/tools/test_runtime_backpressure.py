#!/usr/bin/env python3
"""Exercise real nonblocking firmware send backpressure on test-owned loopback.

Canonical greeting/card traffic and a bounded heartbeat burst are synthetic.
SO_SNDBUF/SO_RCVBUF cause actual kernel EAGAIN, not an injected write return.
This is not production relay, physical timing or a normal heartbeat-rate claim.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from test_capable_pairing import FixtureTraffic, receive
from test_runtime_failures import RuntimeFailureTests
from test_production_views import observation_lines
from runtime_observations import verify_application_sessions


def ping(token):
    frame = bytearray.fromhex('a753032304000000')
    frame[7] = 255 ^ (sum((i + 1) * frame[i] for i in range(7)) & 255)
    return bytes(frame) + token.to_bytes(4, 'little')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    assert not (root/'.agent-workflow/STOP').exists()
    assert args.binary.is_file()
    os.umask(0o077)
    args.evidence.mkdir(parents=True, exist_ok=True)
    fixture = FixtureTraffic()
    assertions = RuntimeFailureTests()
    hellos, reports, errors, schedule = [], [], [], []
    blocked_event = threading.Event()
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
        listener.bind(('127.0.0.1', 0))
        listener.listen(2)
        listener.settimeout(22)
        port = listener.getsockname()[1]

        def serve():
            try:
                with listener.accept()[0] as connection:
                    connection.settimeout(15)
                    assertions.greet(connection, fixture, hellos, 101)
                    # Establish a real active card and a separate retained successor.
                    for payload in assertions.reports(connection, reports, []):
                        if payload[82] == 0:
                            connection.sendall(fixture.frame('external_twitch_kind', seq=2, kind=0,
                                                             ttlDs=60, deadlineRelayMs=65000))
                        elif int.from_bytes(payload[108:112], 'little') == 2:
                            connection.sendall(fixture.frame('external_twitch_kind', seq=3, kind=0,
                                                             ttlDs=1, deadlineRelayMs=65000))
                            break
                    started = time.monotonic()
                    count = 0
                    # Stop reading only the test-owned socket. One small frame per
                    # 10 ms keeps RX/FIFO bursts bounded while the TCP window fills.
                    while not blocked_event.is_set() and time.monotonic() - started < 12:
                        try:
                            connection.sendall(ping(count))
                        except (BrokenPipeError, ConnectionResetError):
                            break
                        count += 1
                        blocked_event.wait(0.01)
                    assert blocked_event.is_set(), 'No actual kernel EAGAIN observed'
                    # Once the real syscall blocks, stop producing responses so
                    # FIFO exhaustion cannot replace the pending-write deadline.
                    # Keep the window closed beyond the unchanged three-second budget.
                    time.sleep(3.5)
                    schedule.append(dict(pingsSent=count, noReadMs=round((time.monotonic()-started)*1000),
                                         receiveBufferBytes=connection.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)))
                    # Drain the accepted prefix after closure; a truncated final
                    # frame is explicitly recorded, not decoded as a complete report.
                    tail = bytearray()
                    while True:
                        data = connection.recv(4096)
                        if not data:
                            break
                        tail.extend(data)
                    (args.evidence/'first-session-prefix.hex').write_text(tail.hex()+'\n')
                    offset = 0
                    while len(tail)-offset >= 8:
                        header = tail[offset:offset+8]
                        assert header[:3] == bytes.fromhex('a75303')
                        assert header[7] == 255 ^ (sum((i+1)*header[i] for i in range(7)) & 255)
                        size = 8 + int.from_bytes(header[4:6], 'little')
                        if len(tail)-offset < size:
                            break
                        if header[3] == 6:
                            reports.append(tail[offset+8:offset+size].hex())
                        offset += size
                    schedule[-1]['truncatedTailBytes'] = len(tail)-offset
                with listener.accept()[0] as connection:
                    connection.settimeout(22)
                    assertions.greet(connection, fixture, hellos, 102, latest=3)
                    for _ in assertions.reports(connection, reports, []):
                        pass
            except BaseException as error:
                errors.append(repr(error))

        thread = threading.Thread(target=serve)
        thread.start()
        env = {k:v for k,v in os.environ.items() if not k.startswith('FIRMWARE_PEER_')}
        env.update(FIRMWARE_PEER_PORT=str(port), FIRMWARE_PEER_SECONDS='22',
                   FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_REQUIRE_DESK='1',
                   FIRMWARE_PEER_SEND_BUFFER_BYTES='1024', FIRMWARE_PEER_MAX_WRITE_BYTES='13')
        lines = []
        with (args.evidence/'native-stderr.log').open('w') as log:
            child = subprocess.Popen([str(args.binary.resolve())],env=env,stdout=subprocess.PIPE,stderr=log,text=True)
            timer = threading.Timer(30, child.kill)
            assert child.stdout is not None
            timer.start()
            try:
                for line in observation_lines(child):
                    lines.append(line)
                    if json.loads(line)['event']=='socket_write_blocked':
                        blocked_event.set()
                child.wait(timeout=5)
            finally:
                timer.cancel()
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=5)
                child.stdout.close()
                thread.join(timeout=25)
                assert not thread.is_alive(), 'Owned worker did not finish'
    stderr = (args.evidence/'native-stderr.log').read_text()
    records = [json.loads(line) for line in lines]
    verify_application_sessions(records)
    raw = dict(records=records,receivedReports=reports,helloPayloads=hellos,schedule=schedule,
               errors=errors,exitCode=child.returncode,stderr=stderr,
               productionRelayVerified=False,physicalVerified=False)
    (args.evidence/'observations.json').write_text(json.dumps(raw,indent=2)+'\n')
    assert not errors, errors
    assert child.returncode == 0, stderr
    assert '[link] down: write timeout' in stderr, stderr
    blocked = [r for r in records if r['event']=='socket_write_blocked']
    transport = next(r for r in records if r['event']=='transport_observation')
    assert blocked and transport['blockedWrites'] > 0 and transport['observationErrors'] == 0
    down = next(r for r in records if r['event']=='link_down' and r['writeTimeout'])
    assert 0 < down['atMs']-blocked[0]['atMs'] <= 3500, 'Pending output deadline exceeded host scheduling allowance'
    ready = [r for r in records if r['event']=='desk_ready']
    assert len(ready)==2 and ready[0]['connection']!=ready[1]['connection']
    assert [r['seq'] for r in records if r['event']=='started']==[2,3]
    summary = next(r for r in records if r['event']=='summary')
    assert all(summary[k]==2 for k in ('started','holds','exits','completed'))
    local = [r for r in records if r['event']=='device_report_written']
    assert len(local)==len(reports)
    for observed, encoded in zip(local,reports):
        payload = bytes.fromhex(encoded)
        layout = fixture.layouts['DEVICE_REPORT']
        fields = {f['name']:int.from_bytes(payload[f['offset']:f['offset']+f['width']],'little')
                  for f in layout['fields'] if f['offset']<100}
        for raw_key, key in (('reportRevision','revision'),('grantTotal','grantTotal'),('processedSeq','processedSeq'),
                             ('enqueuedSeq','enqueuedSeq'),('observedDeviceMs','observedDeviceMs'),
                             ('observedRelayLowerMs','observedRelayLowerMs'),('displayRevision','displayRevision'),
                             ('sourceGeneration','sourceGeneration'),('twitchEpisode','twitchEpisode'),
                             ('baseScreen','baseScreen'),('reason','reason'),('activePhase','phase'),
                             ('pendingCount','pending'),('expiredTotal','expiredTotal'),
                             ('suppressedTotal','suppressedTotal'),('refusedTotal','refusedTotal')):
            assert observed[key]==fields[raw_key], ('Complete received report mismatch',raw_key)
        assert observed['epoch']==f"{fields['relayEpoch']:016x}"
        assert observed['connection']==f"{fields['connectionId']:016x}"
        assert observed['refusedTotal']==observed['expiredTotal']==observed['suppressedTotal']==0
        expected_grant = 8 + observed['enqueuedSeq'] - 1 - observed['pending'] if observed['connection']==ready[0]['connection'] else 8-observed['pending']
        assert observed['grantTotal']==expected_grant
        for index, entry in enumerate(observed['pendingEntries'],start=1):
            offset = 100 + 16 * index
            assert entry['epoch']==f"{int.from_bytes(payload[offset:offset+8],'little'):016x}"
            assert entry['seq']==int.from_bytes(payload[offset+8:offset+12],'little')
            assert entry['origin']==payload[offset+13]
    assert local[-1]['phase']==local[-1]['pending']==0
    views = [r for r in records if r['event']=='application_view']
    assert any((r['baseScreen'],r['reason'])==(1,1) for r in views)
    verdict = dict(passed=True,actualKernelBackpressure=True,blockedWrites=transport['blockedWrites'],
                   readyConnections=len(ready),completedCards=summary['completed'],
                   firstEagainToDisconnectMs=down['atMs']-blocked[0]['atMs'],
                   socketWrittenReports=len(local),remoteReportsMatched=True,schedule=schedule,
                   productionRelayVerified=False,physicalVerified=False,ownedChildReaped=True,ownedListenerClosed=True)
    with socket.socket() as probe:
        assert probe.connect_ex(('127.0.0.1',port)) != 0
    (args.evidence/'verified.json').write_text(json.dumps(verdict,indent=2)+'\n')
    print(json.dumps(verdict,indent=2))


if __name__ == '__main__':
    main()
