"""User-session command line; never prints desktop notification content."""
import argparse
import asyncio
import fcntl
import json
import os
from pathlib import Path
import sys

from . import __version__
from .config import default_path, load
from .runtime import initial_status, instance, private_directory, private_file, run
from .metrics import boottime_ms


def local_status(config):
    directory = private_directory()
    path = directory / ('twitch-screen-client-' + config.source_id + '.json')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return {'state': 'not_running'}
    with os.fdopen(fd, 'rb') as stream:
        private_file(stream.fileno())
        wire = stream.read(16385)
    if len(wire) > 16384:
        raise ValueError('local status exceeds bound')

    def unique_fields(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('duplicate local status field')
            value[key] = item
        return value

    try:
        value = json.loads(wire, object_pairs_hook=unique_fields)
    except RecursionError:
        raise ValueError('invalid local status structure') from None
    states = {
        'capture': {'starting', 'available', 'daemon_unavailable', 'monitor_denied',
                    'disabled', 'unavailable', 'stopped'},
        'cpu': {'starting', 'warmup', 'cpu_unavailable', 'sampling_gap', 'counter_reset',
                'available', 'memory_unavailable', 'disabled', 'stopped'},
        'relay': {'starting', 'clock_unavailable', 'connect_failed', 'response_unavailable',
                  'invalid_response', 'available', 'contract_rejected', 'uncertain_response', 'stopped'},
    }
    allowed = set(initial_status()) | {'pid', 'observedBoottimeMs', 'pendingNotifications',
                                      'pendingNotificationBytes', 'pendingCpu', 'notification_queue_expired'}
    if not isinstance(value, dict) or set(value) - allowed or 'observedBoottimeMs' not in value:
        raise ValueError('invalid local status fields')
    for key, item in value.items():
        if key in states:
            valid = isinstance(item, str) and item in states[key]
        elif key == 'pendingCpu':
            valid = type(item) is bool
        else:
            limit = {'pendingNotifications': 32, 'pendingNotificationBytes': 65536}.get(key)
            valid = type(item) is int and item >= (1 if key == 'pid' else 0)
            valid = valid and (limit is None or item <= limit)
        if not valid:
            raise ValueError('invalid local status value')
    # A stale file never implies a live process; reconcile the exact source lock.
    running = False
    try:
        fd = os.open(directory / ('twitch-screen-client-' + config.source_id + '.lock'),
                     os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        fd = -1
    except OSError:
        raise ValueError('invalid local status lock') from None
    if fd >= 0:
        try:
            private_file(fd)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                running = True
        finally:
            os.close(fd)
    elapsed = boottime_ms() - value['observedBoottimeMs']
    age = elapsed if elapsed >= 0 else None
    fresh = age is not None and age <= 5000
    return {**value, 'state': 'running' if running and fresh else 'stale' if running else 'not_running',
            'observationAgeMs': age}


def main():
    parser = argparse.ArgumentParser(prog='twitch-screen-client')
    parser.add_argument('--version', action='version', version=__version__)
    commands = parser.add_subparsers(dest='command', required=True)
    for command in ('run', 'check-config', 'status'):
        child = commands.add_parser(command)
        child.add_argument('--config', type=Path, default=default_path())
    args = parser.parse_args()
    try:
        config = load(args.config)
        if args.command == 'check-config':
            print('Configuration valid. Notification forwarding ' + ('enabled.' if config.notifications_enabled else 'disabled.'))
        elif args.command == 'status':
            print(json.dumps(local_status(config)))
        else:
            with instance(config.source_id) as status_path:
                asyncio.run(run(config, status_path))
    except (OSError, ValueError) as error:
        # Config/parser exceptions could include input. Do not echo their content.
        print('Client unavailable: ' + type(error).__name__ + '. Check configuration and user-session prerequisites.', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
