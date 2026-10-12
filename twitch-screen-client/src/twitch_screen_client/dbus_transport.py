"""Bounded dbus-fast 5.2.0 low-level transport.

Read exact socket quotas before handing a validated bounded frame to the pinned
codec. Private codec APIs live only here. Capture owns operation deadlines,
reconnection, dispatch and cancellation; this module never replies automatically.
"""
import asyncio
import io
import os
import socket
import struct
from importlib.metadata import version

from dbus_fast import Message, MessageType
from dbus_fast._private.unmarshaller import Unmarshaller

LIMIT = 65536
AUTH_LIMIT = 1024
DEPTH = 64
WORK = 65536


class Rejected(ValueError):
    pass


def envelope(header):
    if len(header) != 16 or header[0] not in (108, 66):
        raise Rejected('fixed header endian/size')
    if header[1] not in (1, 2, 3, 4) or header[2] & ~7 or header[3] != 1:
        raise Rejected('fixed header type/flags/version')
    order = '<' if header[0] == 108 else '>'
    body, serial, fields = struct.unpack_from(order + 'III', header, 4)
    total = 16 + fields + (-fields & 7) + body
    if not serial or total > LIMIT:
        raise Rejected('serial or complete 65536-byte envelope')
    return total, fields, body, order


def signature(text):
    """Small grammar parser with no codec cache and explicit nesting bounds."""
    if len(text) > 255:
        raise Rejected('signature length')
    p = 0

    def one(arrays=0, structs=0, dict_allowed=False):
        nonlocal p
        if p >= len(text):
            raise Rejected('incomplete signature')
        token = text[p]
        p += 1
        if token in 'ybnqiuxtdsogv':
            return (token,)
        if token == 'h':
            raise Rejected('FD type disabled')
        if token == 'a':
            if arrays >= 32:
                raise Rejected('signature array depth')
            return ('a', one(arrays + 1, structs, True))
        if token in '({':
            if structs >= 32 or (token == '{' and not dict_allowed):
                raise Rejected('signature struct/dictionary placement')
            close = ')' if token == '(' else '}'
            children = []
            while p < len(text) and text[p] != close:
                children.append(one(arrays, structs + 1))
            if p >= len(text) or not children:
                raise Rejected('empty/unclosed struct')
            p += 1
            if token == '{' and (len(children) != 2 or children[0][0] not in 'ybnqiuxtdsog'):
                raise Rejected('dictionary key/arity')
            return (token, tuple(children))
        raise Rejected('invalid signature token')

    trees = []
    while p < len(text):
        trees.append(one())
    return trees


class Validator:
    def __init__(self, wire, order):
        self.wire = wire
        self.order = order
        self.pos = 0
        self.work = 0

    def take(self, n, end):
        if n < 0 or self.pos + n > end:
            raise Rejected('internal extent exceeds container')
        start = self.pos
        self.pos += n
        return self.wire[start:self.pos]

    def align(self, n, end):
        if any(self.take(-self.pos & (n - 1), end)):
            raise Rejected('nonzero padding')

    def uint(self, end):
        self.align(4, end)
        return struct.unpack(self.order + 'I', self.take(4, end))[0]

    def value(self, tree, end, depth=0):
        self.work += 1
        if self.work > WORK or depth > DEPTH:
            raise Rejected('decode work/container depth')
        token = tree[0]
        if token in 'ybnqiuxtd':
            size = {'y': 1, 'n': 2, 'q': 2, 'b': 4, 'i': 4, 'u': 4, 'x': 8, 't': 8, 'd': 8}[token]
            self.align(size, end)
            raw = self.take(size, end)
            if token == 'b' and struct.unpack(self.order + 'I', raw)[0] > 1:
                raise Rejected('invalid boolean')
            return None
        if token in 'sog':
            length = self.take(1, end)[0] if token == 'g' else self.uint(end)
            raw = self.take(length, end)
            if self.take(1, end) != b'\0' or b'\0' in raw:
                raise Rejected('string terminator/embedded NUL')
            try:
                text = raw.decode('ascii' if token == 'g' else 'utf-8', errors='strict')
            except UnicodeError as exc:
                raise Rejected('string encoding') from exc
            if token == 'g':
                signature(text)
            return text
        if token == 'v':
            text = self.value(('g',), end, depth + 1)
            types = signature(text)
            if len(types) != 1:
                raise Rejected('variant requires one complete type')
            return text, self.value(types[0], end, depth + 1)
        if token in '({':
            self.align(8, end)
            for child in tree[1]:
                self.value(child, end, depth + 1)
            return None
        if token == 'a':
            length = self.uint(end)
            child = tree[1]
            alignment = {'(': 8, '{': 8, 'x': 8, 't': 8, 'd': 8,
                         'b': 4, 'i': 4, 'u': 4, 's': 4, 'o': 4, 'a': 4,
                         'n': 2, 'q': 2}.get(child[0], 1)
            self.align(alignment, end)
            stop = self.pos + length
            if stop > end:
                raise Rejected('array extent exceeds container')
            while self.pos < stop:
                before = self.pos
                self.value(child, stop, depth + 1)
                if self.pos <= before:
                    raise Rejected('array made no progress')
            if self.pos != stop:
                raise Rejected('array end mismatch')
            return None
        raise Rejected('unsupported type')


def validate(wire):
    total, fields, body, order = envelope(wire[:16])
    if len(wire) != total:
        raise Rejected('incomplete/trailing frame')
    v = Validator(wire, order)
    v.pos = 16
    end = 16 + fields
    seen = set()
    body_signature = ''
    expected = {1: 'o', 2: 's', 3: 's', 4: 's', 5: 'u', 6: 's', 7: 's', 8: 'g', 9: 'u'}
    while v.pos < end:
        v.align(8, end)
        code = v.take(1, end)[0]
        if code == 0 or code in seen:
            raise Rejected('zero/duplicate header field')
        seen.add(code)
        kind, value = v.value(('v',), end)
        if code in expected and kind != expected[code]:
            raise Rejected('header variant type mismatch')
        if code == 9:
            # No negotiation means even a zero FD header is unnecessary.
            raise Rejected('FD header disabled')
        if code == 8:
            body_signature = value
    v.align(8, total)
    if v.pos + body != total:
        raise Rejected('body extent mismatch')
    for tree in signature(body_signature):
        v.value(tree, total)
    if v.pos != total:
        raise Rejected('body/signature end mismatch')
    return v.work


def decode(wire):
    validate(wire)
    # The codec never receives a socket or an unvalidated attacker-sized extent.
    message = Unmarshaller(stream=io.BytesIO(wire), negotiate_unix_fd=False).unmarshall()
    if message is None or message.unix_fds:
        raise Rejected('incomplete codec decode or FD')
    return message


class Connection:
    def __init__(self, path, label):
        self.path = path
        self.label = label
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.setblocking(False)
        self.serial = 0
        self.monitor = False
        self.read_requests = []
        self.auth_commands = []
        self.decoded = 0
        self.sent_after_monitor = 0
        self.unique_name = None

    async def exact(self, count):
        result = bytearray()
        deadline = None
        while len(result) < count:
            quota = count - len(result)
            # Keep instrumentation bounded even during a long capture run.
            if len(self.read_requests) < 256:
                self.read_requests.append(quota)
            chunk_read = asyncio.get_running_loop().sock_recv(self.sock, quota)
            if deadline is None:
                chunk = await chunk_read
                deadline = asyncio.get_running_loop().time() + 3
            else:
                async with asyncio.timeout_at(deadline):
                    chunk = await chunk_read
            if not chunk:
                raise EOFError('peer closed')
            result.extend(chunk)
        return bytes(result)

    async def line(self):
        line = bytearray()
        while len(line) < AUTH_LIMIT:
            line.extend(await self.exact(1))
            if line.endswith(b'\r\n'):
                return bytes(line)
        raise Rejected('authentication line exceeds 1024 bytes')

    async def connect(self):
        try:
            await asyncio.get_running_loop().sock_connect(self.sock, self.path)
            command = b'\0AUTH EXTERNAL ' + str(os.getuid()).encode().hex().encode() + b'\r\n'
            self.auth_commands.append('AUTH EXTERNAL')
            await asyncio.get_running_loop().sock_sendall(self.sock, command)
            reply = await self.line()
            if not reply.startswith(b'OK ') or len(reply.strip().split()) != 2:
                raise Rejected('authentication rejected')
            self.auth_commands.append('BEGIN')
            await asyncio.get_running_loop().sock_sendall(self.sock, b'BEGIN\r\n')
            reply = await self.call(bus_call('Hello'))
            if reply.message_type != MessageType.METHOD_RETURN or reply.signature != 's':
                raise Rejected('Hello failed')
            self.unique_name = reply.body[0]
            return self
        except BaseException:
            self.close()
            raise

    async def receive(self):
        try:
            header = await self.exact(16)
            total, _, _, _ = envelope(header)
            async with asyncio.timeout(3):
                message = decode(header + await self.exact(total - 16))
            self.decoded += 1
            return message
        except BaseException:
            self.close()
            raise

    async def send(self, message):
        if self.monitor:
            self.sent_after_monitor += 1
            raise Rejected('monitor is receive-only')
        self.serial += 1
        message.serial = self.serial
        wire = bytes(message._marshall(negotiate_unix_fd=False))
        validate(wire)
        await asyncio.get_running_loop().sock_sendall(self.sock, wire)
        return message.serial

    async def call(self, message, observe=None):
        serial = await self.send(message)
        while True:
            reply = await self.receive()
            if reply.reply_serial == serial and reply.message_type in (MessageType.METHOD_RETURN, MessageType.ERROR):
                return reply
            if observe:
                observe(reply)

    async def become_monitor(self, rules):
        if not rules:
            raise Rejected('empty monitor rules forbidden')
        reply = await self.call(bus_call('BecomeMonitor', 'asu', [rules, 0], 'org.freedesktop.DBus.Monitoring'))
        if reply.message_type == MessageType.METHOD_RETURN:
            self.monitor = True
        return reply

    def close(self):
        self.sock.close()


def bus_call(member, signature='', body=None, interface='org.freedesktop.DBus'):
    return Message(destination='org.freedesktop.DBus', path='/org/freedesktop/DBus',
                   interface=interface, member=member, signature=signature, body=body or [])


def codec_identity():
    import dbus_fast._private.unmarshaller as module
    assert version('dbus-fast') == '5.2.0'
    assert module.__file__.endswith('.so'), 'compiled codec required for this evidence'
    return {'version': version('dbus-fast'), 'compiledCodec': module.__file__}
