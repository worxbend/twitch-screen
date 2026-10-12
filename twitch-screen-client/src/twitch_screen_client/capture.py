"""Narrow, receive-only notification capture with independent degraded health."""
import asyncio
import os
import random
from urllib.parse import unquote

from dbus_fast import MessageType

from .dbus_transport import Connection, Rejected, bus_call
from .metrics import boottime_ms
from .normalize import normalize

NAME = 'org.freedesktop.Notifications'
PATH = '/org/freedesktop/Notifications'
RULE = "type='method_call',path='/org/freedesktop/Notifications',interface='org.freedesktop.Notifications',member='Notify'"
OWNER_RULE = "type='signal',sender='org.freedesktop.DBus',path='/org/freedesktop/DBus',interface='org.freedesktop.DBus',member='NameOwnerChanged',arg0='org.freedesktop.Notifications'"


def bus_path(address):
    # Local Unix transport only. No TCP bus, subprocess autolaunch or FD negotiation.
    if not isinstance(address, str) or not address.startswith('unix:') or ';' in address:
        raise Rejected('session bus requires one Unix address')
    entries = {}
    for item in address[5:].split(','):
        key, separator, value = item.partition('=')
        if not separator or key in entries or key not in ('path', 'abstract', 'guid'):
            raise Rejected('invalid Unix bus address')
        entries[key] = unquote(value, errors='strict')
    if ('path' in entries) == ('abstract' in entries):
        raise Rejected('Unix bus requires path or abstract')
    value = entries.get('path', entries.get('abstract', ''))
    if not value or '\0' in value or ('path' in entries and not value.startswith('/')):
        raise Rejected('invalid Unix bus path')
    return value if 'path' in entries else '\0' + value


class MonitorDenied(Rejected):
    """Current session explicitly refused scoped monitoring, not a transport error."""


class Capture:
    def __init__(self, config, submit, status, address=None, clock=boottime_ms):
        self.config = config
        self.submit = submit  # Synchronous, constant-bounded queue admission only.
        self.status = status
        self.address = address
        self.clock = clock
        self.owner = ''
        self.generation = 0
        self.connections = []

    def owner_changed(self, message):
        if (message.message_type == MessageType.SIGNAL and message.sender == 'org.freedesktop.DBus'
                and message.path == '/org/freedesktop/DBus' and message.interface == 'org.freedesktop.DBus'
                and message.member == 'NameOwnerChanged' and message.signature == 'sss'
                and message.body[0] == NAME):
            self.owner = message.body[2]
            self.generation += 1
            self.status['capture'] = 'available' if self.owner else 'daemon_unavailable'

    async def session(self):
        path = bus_path(self.address or os.environ.get('DBUS_SESSION_BUS_ADDRESS', ''))
        normal, monitor = Connection(path, 'owner/status'), Connection(path, 'monitor')
        self.connections = [normal, monitor]
        self.owner = ''
        try:
            async with asyncio.timeout(3):
                await normal.connect()
                reply = await normal.call(bus_call('AddMatch', 's', [OWNER_RULE]))
                if reply.message_type != MessageType.METHOD_RETURN:
                    raise Rejected('owner subscription unavailable')
                reply = await normal.call(bus_call('GetNameOwner', 's', [NAME]), self.owner_changed)
                if reply.message_type == MessageType.METHOD_RETURN and reply.signature == 's':
                    self.owner = reply.body[0]
                elif reply.error_name != 'org.freedesktop.DBus.Error.NameHasNoOwner':
                    raise Rejected('owner query unavailable')
                await monitor.connect()
                reply = await monitor.become_monitor([RULE])
                if reply.message_type != MessageType.METHOD_RETURN:
                    self.status['capture'] = 'monitor_denied'
                    raise MonitorDenied('scoped monitoring denied')
            self.generation += 1
            self.status['capture'] = 'available' if self.owner else 'daemon_unavailable'

            async def owners():
                while True:
                    self.owner_changed(await normal.receive())

            async def submissions():
                while True:
                    message = await monitor.receive()
                    if (self.owner and message.message_type == MessageType.METHOD_CALL
                            and message.path == PATH and message.interface == NAME and message.member == 'Notify'
                            and message.destination in (NAME, self.owner) and message.signature == 'susssasa{sv}i'
                            and isinstance(message.sender, str) and message.sender.startswith(':')):
                        card = normalize(message.body, self.config, self.clock())
                        if card is not None:
                            self.status['captured'] += 1
                            self.submit(card)
                    # No return correlation, replacement coalescing or automatic dispatch.

            async with asyncio.TaskGroup() as group:
                group.create_task(owners())
                group.create_task(submissions())
        finally:
            self.owner = ''
            for connection in self.connections:
                connection.close()
            self.connections = []

    async def run(self):
        if not self.config.notifications_enabled:
            self.status['capture'] = 'disabled'
            return
        backoff = 1
        while True:
            try:
                await self.session()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                # Classify this attempt, never retain a historical denial reason
                # when a later bus/auth/transport attempt fails independently.
                self.status['capture'] = 'monitor_denied' if isinstance(error, MonitorDenied) else 'unavailable'
                self.status['capture_failures'] += 1
                # Reconnect only this subsystem. CPU remains independently scheduled.
                await asyncio.sleep(backoff * random.uniform(1, 1.25))
                backoff = min(30, backoff * 2)
