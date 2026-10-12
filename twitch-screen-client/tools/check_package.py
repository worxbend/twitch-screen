"""Validate the wheel's explicit public file allowlist and packaged CLI assets."""
from pathlib import Path
import sys
import zipfile

MODULES = {'__init__.py', '__main__.py', 'capture.py', 'cli.py', 'clock.py', 'config.py',
           'dbus_transport.py', 'delivery.py', 'metrics.py', 'normalize.py', 'queue.py',
           'responses.py', 'runtime.py'}
ASSETS = {'config.example.toml', 'twitch-screen-client.service'}


def check(path):
    root = Path(__file__).resolve().parents[1]
    with zipfile.ZipFile(path) as wheel:
        names = set(wheel.namelist())
        package = {name.split('/', 1)[1] for name in names if name.startswith('twitch_screen_client/')}
        if package != MODULES | ASSETS:
            raise ValueError('wheel package differs from public allowlist')
        for name in names:
            if not (name.startswith('twitch_screen_client/') or
                    name.startswith('twitch_screen_client-0.1.0.dist-info/')):
                raise ValueError('unexpected wheel path')
            if '.agent-workflow' in name or '..' in name.split('/'):
                raise ValueError('private or escaping wheel path')
        for module in MODULES:
            if wheel.read('twitch_screen_client/' + module) != (root / 'src/twitch_screen_client' / module).read_bytes():
                raise ValueError('wheel module differs from current source: ' + module)
        assert wheel.read('twitch_screen_client/config.example.toml') == (root / 'config.example.toml').read_bytes()
        assert wheel.read('twitch_screen_client/twitch-screen-client.service') == (root / 'systemd/twitch-screen-client.service').read_bytes()
        entry = wheel.read('twitch_screen_client-0.1.0.dist-info/entry_points.txt').decode()
        assert 'twitch-screen-client = twitch_screen_client.cli:main' in entry
    print('Wheel public allowlist, example configuration, user unit and CLI entry point verified.')


if __name__ == '__main__':
    check(Path(sys.argv[1]))
