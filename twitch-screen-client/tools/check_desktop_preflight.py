"""Read-only Ubuntu/GNOME companion prerequisites for the exclusive integrator.

Reads OS/package/session metadata and selected systemd unit state. Never connects
to a session bus, calls Notify/BecomeMonitor, reads personal notifications, changes
services or bus policy, accesses providers/board, or installs anything. Ready means
ready for a separately scoped synthetic probe, never desktop/service acceptance.
"""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import sys


def assess(inputs):
    environment = inputs['environment']
    release = inputs['os']
    version = tuple(inputs['python'])
    gnome = re.fullmatch(r'GNOME Shell (\d+)(?:\.[0-9]+)*', inputs['gnome'].strip())
    target = {'ubuntu2404': release.get('ID') == 'ubuntu' and release.get('VERSION_ID') == '24.04',
              'gnome46': bool(gnome and gnome[1] == '46'),
              'wayland': environment.get('XDG_SESSION_TYPE') == 'wayland',
              'gnomeSession': 'gnome' in environment.get('XDG_CURRENT_DESKTOP', '').casefold().split(':'),
              'python312OrNewer': version >= (3, 12),
              'pinnedDependencies': all(inputs['dependencies'].get(name) == expected
                                        for name, expected in (('dbus-fast', '5.2.0'), ('httpx', '0.28.1'))),
              'privateRuntimeDirectory': inputs['runtimeDirectoryPrivate'],
              'sessionBusSocketPresent': inputs['busSocketPresent']}
    service = {key: inputs['service'].get(key, 'unknown') for key in ('LoadState', 'ActiveState', 'SubState')}
    required = ['synthetic_only_filters_and_owned_foreground_process', 'actual_narrow_monitor_permission',
                'ordinary_supported_desktop_popup_observation', 'actual_installed_user_service_start_stop',
                'exact_docker_native_admin_pairing', 'physical_notify_cpu_dashboard_evidence']
    if service['ActiveState'] == 'active':
        required.append('preserve_existing_service')
    return {'status': 'ready_for_scoped_probe' if all(target.values()) else 'blocked',
            'targetPrerequisitesReady': all(target.values()), 'prerequisites': target,
            'os': {'id': release.get('ID', 'unknown'), 'version': release.get('VERSION_ID', 'unknown')},
            'python': '.'.join(str(item) for item in version), 'gnomeMajor': gnome[1] if gnome else None,
            'dependencies': inputs['dependencies'], 'service': service,
            'blockers': [key for key, ready in target.items() if not ready], 'requiredEvidence': required,
            'capturePermission': 'not_probed', 'desktopVerified': False, 'serviceVerified': False,
            'physicalVerified': False, 'productAccepted': False, 'allSoftwareVerified': False}


def private_runtime(path):
    try:
        attributes = Path(path).lstat()
        return stat.S_ISDIR(attributes.st_mode) and attributes.st_uid == os.getuid() and not attributes.st_mode & 0o077
    except (OSError, ValueError):
        return False


def session_socket(environment):
    address = environment.get('DBUS_SESSION_BUS_ADDRESS', '')
    # Admit only one explicit local Unix-path bus; do not connect or print its address.
    if not address.startswith('unix:path=') or ';' in address:
        return False
    path = address[len('unix:path='):].split(',', 1)[0]
    if '%' in path or not Path(path).is_absolute():
        return False
    try:
        attributes = Path(path).lstat()
        return stat.S_ISSOCK(attributes.st_mode) and attributes.st_uid == os.getuid()
    except OSError:
        return False


def metadata(command):
    try:
        response = subprocess.run(command, capture_output=True, text=True, timeout=3, check=False)
        if response.returncode == 0 and len(response.stdout) <= 4096:
            return response.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return ''


def collect():
    dependencies = {}
    for name in ('dbus-fast', 'httpx', 'twitch-screen-client'):
        try:
            dependencies[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            dependencies[name] = 'not_installed'
    try:
        release = platform.freedesktop_os_release()
    except OSError:
        release = {}
    output = metadata(['systemctl', '--user', 'show', 'twitch-screen-client.service',
                       '--property=LoadState,ActiveState,SubState'])
    service = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    return assess({'os': release, 'gnome': metadata(['gnome-shell', '--version']),
                   'python': sys.version_info[:2], 'environment': os.environ,
                   'dependencies': dependencies,
                   'runtimeDirectoryPrivate': private_runtime(os.environ.get('XDG_RUNTIME_DIR', '')),
                   'busSocketPresent': session_socket(os.environ), 'service': service})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if (root / '.agent-workflow/STOP').exists():
        parser.error('workflow STOP is present')
    os.umask(0o077)
    result = collect()
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(result, indent=2))
    return 0 if result['targetPrerequisitesReady'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
