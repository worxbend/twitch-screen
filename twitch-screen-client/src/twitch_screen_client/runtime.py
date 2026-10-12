"""Foreground task ownership, private local diagnostics and source instance lock."""
import asyncio
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import signal
import stat
import tempfile

from .capture import Capture
from .delivery import Delivery
from .metrics import Sampler, boottime_ms
from .queue import CardQueue


def private_directory():
    root = os.environ.get('XDG_RUNTIME_DIR')
    if not root:
        raise ValueError('XDG_RUNTIME_DIR is required')
    directory = Path(root)
    info = directory.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('XDG_RUNTIME_DIR must be owned by this user and private')
    return directory


def private_file(fd):
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('local diagnostics member must be a private regular file')


@contextmanager
def instance(source):
    directory = private_directory()
    fd = os.open(directory / ('twitch-screen-client-' + source + '.lock'),
                 os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    try:
        private_file(fd)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('a companion instance already owns this source_id') from None
        yield directory / ('twitch-screen-client-' + source + '.json')
    finally:
        os.close(fd)


def initial_status():
    return dict.fromkeys(('captured', 'capture_failures', 'notification_accepted', 'notification_ambiguous',
                          'notification_expired', 'notification_refused', 'notification_rejected',
                          'cpu_accepted', 'cpu_expired', 'cpu_dropped'), 0) | {
                              'capture': 'starting', 'cpu': 'starting', 'relay': 'starting'}


def write_status(path, status):
    wire = json.dumps(status, allow_nan=False).encode()
    if len(wire) > 16384:
        raise ValueError('status exceeds bound')
    # Exclusive creation never truncates or opens a caller-supplied staging member.
    fd, name = tempfile.mkstemp(prefix=path.name + '.', suffix='.new', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'wb') as stream:
            fd = -1
            stream.write(wire)
        os.replace(temporary, path)
    finally:
        if fd >= 0:
            os.close(fd)
        temporary.unlink(missing_ok=True)


async def run(config, status_path):
    status = initial_status()
    cards = CardQueue(boottime_ms)
    delivery = Delivery(config, boottime_ms, cards, status)
    capture = Capture(config, delivery.submit, status)
    sampler = Sampler(config.sample_interval_ms)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    registered_signals = []

    def diagnostic():
        status.update(pid=os.getpid(), observedBoottimeMs=boottime_ms(),
                      pendingNotifications=len(cards.cards), pendingNotificationBytes=cards.bytes,
                      pendingCpu=delivery.cpu is not None, notification_queue_expired=cards.expired)
        write_status(status_path, status)

    async def measurements():
        if not config.cpu_enabled:
            status['cpu'] = 'disabled'
            return
        while True:
            result = sampler.sample()
            status['cpu'] = sampler.reason
            if result is not None:
                delivery.sample(*result)
            diagnostic()
            await asyncio.sleep(config.sample_interval_ms / 1000)

    async def diagnostics():
        # Local diagnostics timer is not relay/API health polling.
        while True:
            diagnostic()
            await asyncio.sleep(1)

    tasks = []
    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop.set)
            registered_signals.append(sig)
        async with asyncio.TaskGroup() as group:
            tasks = [group.create_task(worker) for worker in (
                capture.run(), measurements(), delivery.notifications(), delivery.metrics(), diagnostics())]
            await stop.wait()
            for task in tasks:
                task.cancel()
    finally:
        try:
            async with asyncio.timeout(4):
                await delivery.close()
        finally:
            # Local ownership cleanup must also run after client errors/timeouts.
            for sig in registered_signals:
                loop.remove_signal_handler(sig)
            status['capture'], status['cpu'], status['relay'] = 'stopped', 'stopped', 'stopped'
            diagnostic()
