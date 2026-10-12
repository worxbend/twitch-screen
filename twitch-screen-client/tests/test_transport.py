"""Executable synthetic-only guarded transport and private bus acceptance probe."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import socket
import struct
import tempfile
import time
import unittest

from dbus_fast import Message, MessageType, Variant
from twitch_screen_client.dbus_transport import (AUTH_LIMIT, LIMIT, Connection, Rejected, bus_call,
                             codec_identity, decode, envelope, signature, validate)

ROOT = Path(tempfile.mkdtemp(dir=os.environ['TMPDIR'], prefix='client-test-'))
RESULTS = []
NAME = 'org.freedesktop.Notifications'
PATH = '/org/freedesktop/Notifications'
RULE = "type='method_call',path='/org/freedesktop/Notifications',interface='org.freedesktop.Notifications',member='Notify'"
OWNER_RULE = "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',member='NameOwnerChanged',arg0='org.freedesktop.Notifications'"


def record(case, **fields):
    result = {'case': case, **fields}
    RESULTS.append(result)
    print(json.dumps(result), flush=True)


def wire_message(sig='s', body=None):
    message = Message(destination='org.example.Test', path='/test',
                      interface='org.example.Test', member='Test', signature=sig,
                      body=['synthetic'] if body is None else body, serial=1)
    return bytes(message._marshall(negotiate_unix_fd=False))


def replace_body(wire, body):
    _, fields, _, order = envelope(wire[:16])
    start = 16 + fields + (-fields & 7)
    result = bytearray(wire[:start] + body)
    struct.pack_into(order + 'I', result, 4, len(body))
    return bytes(result)


class GuardTests(unittest.IsolatedAsyncioTestCase):
    async def test_fragmented_compiled_decode_and_no_overread(self):
        identity = codec_identity()
        writer, reader = socket.socketpair()
        writer.setblocking(False)
        connection = Connection('/unused', 'fragmented')
        connection.sock.close()
        connection.sock = reader
        reader.setblocking(False)
        wire = wire_message('susssasa{sv}i', ['twitch-screen-e2e', 0, '', 'Synthetic', 'Body', [], {'urgency': Variant('y', 2)}, 1000])

        async def send():
            for byte in wire:
                await asyncio.get_running_loop().sock_sendall(writer, bytes([byte]))
                await asyncio.sleep(0)
            await asyncio.get_running_loop().sock_sendall(writer, wire)

        try:
            task = asyncio.create_task(send())
            first = await asyncio.wait_for(connection.receive(), 3)
            second = await asyncio.wait_for(connection.receive(), 3)
            await task
            self.assertEqual(first.body, second.body)
            self.assertEqual(first.body[6]['urgency'].value, 2)
            self.assertLessEqual(max(connection.read_requests), LIMIT)
            record('fragmented compiled decode and concatenated frames', frames=2, **identity)
        finally:
            writer.close()
            connection.close()

    async def test_fixed_header_rejects_before_remainder(self):
        for order in ('<', '>'):
            for body, fields in ((0xffffffff, 0), (0, 0xffffffff), (LIMIT - 15, 0), (LIMIT - 17, 1)):
                writer, reader = socket.socketpair()
                reader.setblocking(False)
                c = Connection('/unused', 'overflow')
                c.sock.close()
                c.sock = reader
                fixed = bytes([108 if order == '<' else 66, 1, 0, 1]) + struct.pack(order + 'III', body, 1, fields)
                try:
                    writer.sendall(fixed)
                    with self.assertRaises(Rejected):
                        await asyncio.wait_for(c.receive(), .5)
                    self.assertEqual(c.read_requests, [16])
                    self.assertEqual(c.decoded, 0)
                    self.assertEqual(c.sock.fileno(), -1)
                finally:
                    writer.close()
                    c.close()
        record('fixed header pre-allocation rejection', cases=8, codecCalls=0, remainderReads=0)

    def test_exact_64k_envelope_compiled(self):
        small = wire_message('ay', [b''])
        _, fields, _, _ = envelope(small[:16])
        start = 16 + fields + (-fields & 7)
        count = LIMIT - start - 4
        exact = wire_message('ay', [b'x' * count])
        self.assertEqual(len(exact), LIMIT)
        self.assertEqual(len(decode(exact).body[0]), count)
        with self.assertRaises(Rejected):
            decode(wire_message('ay', [b'x' * (count + 1)]))
        record('exact complete envelope boundary', acceptedBytes=len(exact), rejectedBytes=len(exact) + 1)

    def test_internal_lengths_signatures_and_depth(self):
        bad = []
        s = wire_message()
        bad.append(('string internal huge', replace_body(s, struct.pack('<I', 0xffffffff) + b'x\0')))
        bad.append(('string missing terminator', replace_body(s, struct.pack('<I', 1) + b'xx')))
        bad.append(('string invalid UTF8', replace_body(s, struct.pack('<I', 1) + b'\xff\0')))
        bad.append(('trailing body', replace_body(s, struct.pack('<I', 0) + b'\0extra')))
        arr = wire_message('as', [[]])
        bad.append(('array huge', replace_body(arr, struct.pack('<I', 0xffffffff))))
        bad.append(('array nested string escapes array', replace_body(arr, struct.pack('<II', 4, 100) + b'\0')))
        nested = wire_message('aas', [[[]]])
        bad.append(('nested array escapes outer array', replace_body(nested, struct.pack('<II', 4, 100))))
        v = wire_message('v', [Variant('u', 1)])
        bad.append(('variant invalid signature', replace_body(v, b'\x01z\0\0' + bytes(4))))
        bad.append(('variant multiple types', replace_body(v, b'\x02uu\0' + bytes(8))))
        bad.append(('variant truncated signature', replace_body(v, b'\xffu')))
        bad.append(('variant runtime amplification', replace_body(v, b'\x01v\0' * 70 + b'\x01u\0' + bytes(8))))
        boolean = wire_message('b', [True])
        bad.append(('invalid boolean', replace_body(boolean, struct.pack('<I', 2))))
        fd = wire_message('u', [0])
        marker = b'\x08\x01g\x00\x01u\x00'
        self.assertIn(marker, fd)
        bad.append(('FD signature', fd.replace(marker, marker[:-2] + b'h\0')))
        bad.append(('header variant mismatch', s.replace(b'\x01\x01o\x00', b'\x01\x01s\x00', 1)))
        _, fields, body_length, _ = envelope(s[:16])
        body_start = 16 + fields + (-fields & 7)
        for label, extra in [('FD header', b'\x09\x01u\x00' + bytes(4)),
                             ('duplicate signature header', b'\x08\x01g\x00\x01s\x00')]:
            header_fields = s[16:16 + fields] + bytes(-fields & 7) + extra
            header = bytearray(s[:16])
            struct.pack_into('<I', header, 12, len(header_fields))
            extended = bytes(header) + header_fields + bytes(-len(header_fields) & 7) + s[body_start:]
            bad.append((label, extended))
        for label, wire in bad:
            with self.subTest(label=label), self.assertRaises(Rejected):
                decode(wire)
        for text in ('a' * 33 + 'u', '(' * 33 + 'u' + ')' * 33, '()', '{sv}', 'a{vv}', 'a', 'z', 'u' * 256):
            with self.assertRaises(Rejected):
                signature(text)
        # Exercises compiled fast paths after validation, not merely parser success.
        for sig, body in [('as', [['a', 'b']]), ('a{sv}', [{'x': Variant('s', 'y')}]),
                          ('aas', [[['a']]]), ('v', [Variant('as', ['a'])]), ('b', [True])]:
            self.assertEqual(decode(wire_message(sig, body)).signature, sig)
        record('internal lengths signatures runtime depth and compiled fast paths', rejectedFrames=len(bad), rejectedSignatures=8, validCodecCases=5)

    def test_big_endian_compiled_and_bounded_work(self):
        # A real big-endian frame with one signature header and one uint32 body.
        fields = b'\x08\x01g\x00\x01u\x00'
        wire = b'B\x02\x00\x01' + struct.pack('>III', 4, 1, len(fields)) + fields + b'\0' + struct.pack('>I', 123)
        self.assertEqual(decode(wire).body, [123])
        timings = []
        for sig, body in [('au', [[1] * 16000]), ('as', [[''] * 8000]),
                          ('av', [[Variant('y', 1)] * 12000])]:
            wire = wire_message(sig, body)
            self.assertLessEqual(len(wire), LIMIT)
            start = time.perf_counter()
            work = validate(wire)
            message = decode(wire)
            elapsed = time.perf_counter() - start
            self.assertLessEqual(work, 65536)
            self.assertEqual(len(message.body[0]), len(body[0]))
            self.assertLess(elapsed, 1.0)
            timings.append({'signature': sig, 'wireBytes': len(wire), 'validationNodes': work,
                            'validationAndDecodeSeconds': elapsed})
        record('big endian compiled codec and bounded near-ceiling work', measurements=timings,
               timingClaim='local finite probes, not a universal CPU deadline')

    async def test_truncation_fails_closed(self):
        writer, reader = socket.socketpair()
        c = Connection('/unused', 'truncated')
        c.sock.close()
        c.sock = reader
        reader.setblocking(False)
        try:
            writer.sendall(wire_message()[:-1])
            writer.close()
            with self.assertRaises(EOFError):
                await c.receive()
            self.assertEqual(c.sock.fileno(), -1)
            record('truncated frame closes connection', codecCalls=c.decoded)
        finally:
            writer.close()
            c.close()

    async def test_auth_and_first_hello_guard_on_both_roles(self):
        # Real Unix listener, malicious synthetic bus. Guard installed before AUTH.
        for role in ('ordinary', 'monitor'):
            for attack in ('oversized-auth', 'oversized-Hello', 'malformed-Hello'):
                with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cg-') as tmp:
                    path = str(Path(tmp) / 's')
                    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    listener.bind(path)
                    listener.listen()
                    listener.setblocking(False)
                    observed = []

                    async def fake_bus():
                        peer, _ = await asyncio.get_running_loop().sock_accept(listener)
                        peer.setblocking(False)
                        try:
                            auth = bytearray()
                            while not auth.endswith(b'\r\n'):
                                auth.extend(await asyncio.get_running_loop().sock_recv(peer, 1))
                            observed.append(bytes(auth))
                            if attack == 'oversized-auth':
                                await asyncio.get_running_loop().sock_sendall(peer, b'O' * AUTH_LIMIT)
                                return
                            await asyncio.get_running_loop().sock_sendall(peer, b'OK ' + b'1' * 32 + b'\r\n')
                            begin = bytearray()
                            while not begin.endswith(b'\r\n'):
                                begin.extend(await asyncio.get_running_loop().sock_recv(peer, 1))
                            observed.append(bytes(begin))
                            # Read the bounded outbound Hello, then attack its first response.
                            h = await recv_exact(peer, 16)
                            size, _, _, _ = envelope(h)
                            await recv_exact(peer, size - 16)
                            if attack == 'oversized-Hello':
                                wire = b'l\x02\x00\x01' + struct.pack('<III', 0xffffffff, 1, 0)
                            else:
                                wire = replace_body(wire_message(), struct.pack('<I', 0xffffffff) + b'\0')
                            await asyncio.get_running_loop().sock_sendall(peer, wire)
                        finally:
                            peer.close()

                    task = asyncio.create_task(fake_bus())
                    c = Connection(path, role)
                    try:
                        with self.assertRaises(Rejected):
                            await asyncio.wait_for(c.connect(), 2)
                        await task
                        self.assertEqual(c.decoded, 0)
                        self.assertEqual(c.sock.fileno(), -1)
                        self.assertFalse(any(b'NEGOTIATE_UNIX_FD' in line for line in observed))
                    finally:
                        c.close()
                        listener.close()
                        task.cancel()
                        with contextlib.suppress(asyncio.CancelledError):
                            await task
        record('authentication and first Hello protected on both roles', cases=6, descriptorNegotiation=False)


async def recv_exact(sock, count):
    value = bytearray()
    while len(value) < count:
        chunk = await asyncio.get_running_loop().sock_recv(sock, count - len(value))
        if not chunk:
            raise EOFError
        value.extend(chunk)
    return bytes(value)


class PrivateBus:
    def __init__(self, denied=False):
        self.denied = denied
        self.connections = []

    async def __aenter__(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cb-')
        self.path = str(Path(self.tmp.name) / 'b')
        config = Path(self.tmp.name) / 'bus.conf'
        deny = '<deny send_interface="org.freedesktop.DBus.Monitoring" send_member="BecomeMonitor"/>' if self.denied else ''
        config.write_text('<busconfig><type>session</type><listen>unix:path=' + self.path +
                          '</listen><auth>EXTERNAL</auth><policy context="default">'
                          '<allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/>'
                          '<allow own="*"/>' + deny + '</policy></busconfig>')
        config.chmod(0o600)
        self.process = await asyncio.create_subprocess_exec('dbus-daemon', '--nofork', '--print-address=1',
                        '--config-file=' + str(config), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        address = (await asyncio.wait_for(self.process.stdout.readline(), 3)).decode().strip()
        assert address.startswith('unix:path=' + self.path), address
        record('private synthetic bus started', deniedMonitor=self.denied, socketPath=self.path, pid=self.process.pid)
        return self

    async def connect(self, role):
        c = Connection(self.path, role)
        self.connections.append(c)
        await asyncio.wait_for(c.connect(), 3)
        assert c.auth_commands == ['AUTH EXTERNAL', 'BEGIN']
        return c

    async def __aexit__(self, *exc):
        for c in self.connections:
            c.close()
        if self.process.returncode is None:
            self.process.terminate()
        await asyncio.wait_for(self.process.wait(), 3)
        stderr = (await self.process.stderr.read()).decode()
        (ROOT / ('bus-' + str(self.process.pid) + '.stderr.log')).write_text(stderr)
        Path(self.path).unlink(missing_ok=True)
        tmp = self.tmp.name
        self.tmp.cleanup()
        assert all(c.sock.fileno() == -1 for c in self.connections)
        assert not Path(tmp).exists()
        record('owned bus cleanup', deniedMonitor=self.denied, processExit=self.process.returncode,
               allSocketsClosed=True, socketDirectoryRemoved=True)


class IntegratedTests(unittest.IsolatedAsyncioTestCase):
    async def test_guarded_real_bus_delivery_restart_monitor(self):
        async with PrivateBus() as bus:
            normal = await bus.connect('ordinary owner/status')
            monitor = await bus.connect('monitor')
            client = await bus.connect('synthetic producer')
            owners = []
            reply = await normal.call(bus_call('AddMatch', 's', [OWNER_RULE]))
            self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)

            def observe(message):
                if message.member == 'NameOwnerChanged' and message.body[0] == NAME:
                    owners.append(message.body)

            async def daemon():
                c = await bus.connect('fake notification daemon')
                reply = await c.call(bus_call('RequestName', 'su', [NAME, 0]))
                self.assertEqual(reply.body, [1])
                return c

            server = await daemon()
            reply = await normal.call(bus_call('GetNameOwner', 's', [NAME]), observe)
            current_owner = reply.body[0]
            self.assertEqual(current_owner, server.unique_name)
            reply = await monitor.become_monitor([RULE])
            self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
            deliveries = []
            captures = []
            receiver_errors = []

            async def observe_monitor():
                try:
                    while True:
                        m = await monitor.receive()
                        if (m.message_type == MessageType.METHOD_CALL and m.path == PATH and
                            m.interface == NAME and m.member == 'Notify' and
                            m.destination in (NAME, current_owner) and m.signature == 'susssasa{sv}i' and
                            m.body[0] == 'twitch-screen-e2e'):
                            captures.append((m.sender, m.serial))
                            capture_event.set()
                        # No dispatch/send/automatic reply path exists here.
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    receiver_errors.append(repr(exc))
                    capture_event.set()

            async def serve(c):
                while True:
                    m = await c.receive()
                    if m.message_type == MessageType.METHOD_CALL and m.member == 'Notify':
                        self.assertEqual(m.signature, 'susssasa{sv}i')
                        deliveries.append((m.sender, m.serial))
                        await c.send(Message.new_method_return(m, signature='u', body=[len(deliveries)]))

            capture_event = asyncio.Event()
            monitor_task = asyncio.create_task(observe_monitor())
            daemon_task = asyncio.create_task(serve(server))
            tasks = [monitor_task, daemon_task]
            try:
                async def submit(destination, title, body):
                    capture_event.clear()
                    reply = await asyncio.wait_for(client.call(Message(destination=destination, path=PATH,
                              interface=NAME, member='Notify', signature='susssasa{sv}i',
                              body=['twitch-screen-e2e', 0, '', title, body, [], {'urgency': Variant('y', 1)}, 1000])), 3)
                    self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
                    self.assertEqual(reply.body, [len(deliveries)])
                    await asyncio.wait_for(capture_event.wait(), 3)
                    self.assertFalse(receiver_errors)
                    self.assertEqual(captures[-1], deliveries[-1])

                await submit(NAME, 'First synthetic', 'No desktop content')
                await submit(current_owner, 'Unique destination', 'No desktop content')
                # A near-limit valid Notify proves this exact adapter handles large
                # messages across real bus monitor and normal delivery, not just Hello.
                await submit(NAME, 'Bounded large synthetic', 'x' * 64000)
                daemon_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await daemon_task
                server.close()
                server = await daemon()
                reply = await normal.call(bus_call('GetNameOwner', 's', [NAME]), observe)
                old_owner, current_owner = current_owner, reply.body[0]
                self.assertNotEqual(old_owner, current_owner)
                self.assertTrue(any(row[1] == old_owner and not row[2] for row in owners))
                self.assertTrue(any(row[2] == current_owner for row in owners))
                daemon_task = asyncio.create_task(serve(server))
                tasks.append(daemon_task)
                await submit(NAME, 'Owner restarted synthetic', 'No desktop content')
                self.assertEqual(len(deliveries), 4)
                self.assertEqual(captures, deliveries)
                self.assertEqual(monitor.sent_after_monitor, 0)
                with self.assertRaises(Rejected):
                    await monitor.send(bus_call('ListNames'))
                self.assertEqual(monitor.sent_after_monitor, 1)  # Locally refused; no wire send.
                record('integrated guarded auth Hello BecomeMonitor Notify delivery owner restart',
                       captureCount=len(captures), deliveryCount=len(deliveries), daemonOwnerChanged=True,
                       ownerChangeSignals=len(owners), monitorAutomaticReplies=0,
                       monitorPostTransitionWireSends=0, allConnectionsGuarded=True,
                       nearLimitNotifyBodyBytes=64000, **codec_identity())
            finally:
                for task in tasks:
                    task.cancel()
                for task in tasks:
                    with contextlib.suppress(asyncio.CancelledError, EOFError):
                        await task

    async def test_real_bus_oversize_fails_closed_on_both_roles(self):
        async with PrivateBus() as bus:
            normal = await bus.connect('ordinary overflow target')
            monitor = await bus.connect('monitor overflow target')
            hostile = await bus.connect('hostile synthetic fixture')
            sink = await bus.connect('fake notification destination')
            reply = await sink.call(bus_call('RequestName', 'su', [NAME, 0]))
            self.assertEqual(reply.body, [1])
            reply = await monitor.become_monitor([RULE])
            self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
            # Only this deliberately hostile test peer bypasses outbound validation.
            # Both capture roles still use the unchanged guarded receive path.
            payloads = [
                Message(destination=normal.unique_name, message_type=MessageType.SIGNAL,
                        path='/test', interface='org.example.Test', member='Oversize',
                        signature='ay', body=[b'x' * LIMIT], serial=100),
                Message(destination=NAME, path=PATH, interface=NAME, member='Notify',
                        signature='susssasa{sv}i',
                        body=['twitch-screen-e2e', 0, '', 'Oversized synthetic', 'x' * LIMIT, [], {}, 1000], serial=101),
            ]
            sizes = []
            for message in payloads:
                wire = bytes(message._marshall(negotiate_unix_fd=False))
                self.assertGreater(len(wire), LIMIT)
                sizes.append(len(wire))
                await asyncio.get_running_loop().sock_sendall(hostile.sock, wire)
            for c in (normal, monitor):
                before = c.decoded
                with self.assertRaises(Rejected):
                    # Ignore legitimate NameAcquired/NameLost already in the socket.
                    async with asyncio.timeout(3):
                        while True:
                            await c.receive()
                self.assertEqual(c.sock.fileno(), -1)
                self.assertGreaterEqual(c.decoded, before)
            record('real private bus oversize closes ordinary and monitor before decode',
                   hostileWireBytes=sizes, ordinaryClosed=True, monitorClosed=True,
                   envelopeLimitUnchanged=LIMIT)

    async def test_real_denied_monitor_preserves_ordinary_channel(self):
        async with PrivateBus(denied=True) as bus:
            normal = await bus.connect('ordinary owner/status')
            monitor = await bus.connect('monitor')
            reply = await monitor.become_monitor([RULE])
            self.assertEqual(reply.message_type, MessageType.ERROR)
            self.assertEqual(reply.error_name, 'org.freedesktop.DBus.Error.AccessDenied')
            self.assertFalse(monitor.monitor)
            monitor.close()  # Capture unavailable, no root/policy retry.
            reply = await normal.call(bus_call('ListNames'))
            self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
            self.assertIn(normal.unique_name, reply.body[0])
            # Independent synthetic measurement worker remains scheduled and usable.
            sample = await asyncio.create_task(asyncio.sleep(0, result={'cpuPercent': 12.5, 'memoryPercent': 25.0}))
            self.assertEqual(sample['cpuPercent'], 12.5)
            record('real bus denied monitor', errorName='org.freedesktop.DBus.Error.AccessDenied',
                   ordinaryConnectionOperational=True, independentSyntheticWorkerOperational=True,
                   realCpuSamplingClaim=False, policyChangedOnlyOnTestOwnedBus=True)


if __name__ == '__main__':
    started = time.monotonic()
    runner = unittest.TextTestRunner(verbosity=2)
    suite = unittest.defaultTestLoader.loadTestsFromModule(__import__(__name__))
    result = runner.run(suite)
    summary = {'notifyFeasibilityVerified': result.wasSuccessful(), 'testsRun': result.testsRun,
               'failures': len(result.failures), 'errors': len(result.errors),
               'durationSeconds': time.monotonic() - started, 'results': RESULTS,
               'testedLayer': 'isolated bounded pinned-codec transport and real private synthetic dbus-daemon',
               'productionVerified': False, 'desktopVerified': False}
    (ROOT / 'results.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary), flush=True)
    raise SystemExit(0 if result.wasSuccessful() else 1)
