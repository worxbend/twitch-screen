"""Strict, secret-free TOML configuration."""
from dataclasses import dataclass, field
import os
from pathlib import Path
import re
import socket
import tomllib
from urllib.parse import urlsplit


class ConfigError(ValueError):
    """Invalid configuration; messages never contain notification content."""


@dataclass(frozen=True)
class Config:
    relay_url: str
    source_id: str
    host: str = field(default_factory=lambda: socket.gethostname().split('.')[0])
    sample_interval_ms: int = 2000
    cpu_enabled: bool = True
    notifications_enabled: bool = False
    forward_body: bool = False
    include_apps: tuple[str, ...] = ()
    exclude_apps: tuple[str, ...] = ()

    @property
    def freshness_ms(self):
        return min(60000, max(10000, 3 * self.sample_interval_ms))

    def permits(self, app):
        name = app.casefold()
        return (self.notifications_enabled and name != 'twitch-screen-client'
                and name not in self.exclude_apps
                and (not self.include_apps or name in self.include_apps))


def default_path():
    return Path(os.environ.get('XDG_CONFIG_HOME', Path.home() / '.config')) / 'twitch-screen-client/config.toml'


def load(path):
    with Path(path).open('rb') as stream:
        raw = stream.read(16385)
    if len(raw) > 16384:
        raise ConfigError('configuration exceeds 16 KiB')
    return parse(tomllib.loads(raw.decode('utf-8')))


def parse(data):
    allowed = set(Config.__dataclass_fields__)
    if not isinstance(data, dict) or set(data) - allowed:
        raise ConfigError('unknown configuration fields')
    if not all(key in data for key in ('relay_url', 'source_id')):
        raise ConfigError('relay_url and source_id are required')
    url = data['relay_url']
    if not isinstance(url, str) or any(ord(c) <= 32 or c == '\\' for c in url):
        raise ConfigError('invalid relay_url')
    try:
        parsed = urlsplit(url)
        port = parsed.port
        if (parsed.scheme not in ('http', 'https') or not parsed.hostname or
                parsed.username is not None or parsed.password is not None or
                parsed.path not in ('', '/') or parsed.query or parsed.fragment or
                '?' in url or '#' in url or (port is not None and not 1 <= port <= 65535)):
            raise ValueError
        parsed.hostname.encode('idna')
    except (ValueError, UnicodeError):
        raise ConfigError('relay_url requires HTTP(S), a host and root path without credentials/query/fragment') from None
    source = data['source_id']
    if not isinstance(source, str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,31}', source):
        raise ConfigError('invalid source_id')
    values = dict(data)
    values['relay_url'] = url.rstrip('/')
    for key in ('cpu_enabled', 'notifications_enabled', 'forward_body'):
        if key in values and type(values[key]) is not bool:
            raise ConfigError(key + ' must be boolean')
    interval = values.get('sample_interval_ms', 2000)
    if type(interval) is not int or not 1000 <= interval <= 60000:
        raise ConfigError('sample_interval_ms must be 1000..60000')
    host = values.get('host', socket.gethostname().split('.')[0])
    if (not isinstance(host, str) or not host.strip() or len(host) > 128 or
            any(0xD800 <= ord(c) <= 0xDFFF or ord(c) < 32 for c in host)):
        raise ConfigError('invalid host label')
    for key in ('include_apps', 'exclude_apps'):
        apps = values.get(key, [])
        if (not isinstance(apps, list) or len(apps) > 128 or
                any(not isinstance(app, str) or not app.strip() or len(app) > 128 for app in apps)):
            raise ConfigError(key + ' must be a bounded array of application names')
        values[key] = tuple(app.casefold() for app in apps)
    return Config(**values)
