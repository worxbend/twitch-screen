#!/usr/bin/env python3
"""Read-only H preflight. Never flash, capture, enable services or control broadcasts."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = (
    'disabled-generic-orbit-loss-recovery', 'simulated-overlay-control',
    'weather-cpu-hidden-update-expiry', 'queue-credit-report-recovery',
    'admin-mobile-feed-reconnect', 'synthetic-desktop-to-lcd',
    'measured-cpu-to-lcd', 'worxbend-obs-live-offline', 'lcd-resource-soak',
)

# These are acceptance inputs, not permission prompts or evidence inferred from tool presence.
INPUTS = {
    'target_configuration': 'Private Wi-Fi and reachable relay settings in a matching local credentials.h; never guessed or overwritten.',
    'target_ownership': 'Verified board identity, pin map, serial path and exclusive resource ownership.',
    'lcd_observation': 'Actual LCD photo/video or explicit human observation of the named scenario.',
    'resource_soak': 'Measured 60-minute mixed-mode heap/block/stack/watchdog and loop/render timing evidence.',
    'mobile_https': 'Real phone/tablet with consenting CA trust, private reachability and exact registered HTTPS callback.',
    'supported_desktop': 'Ubuntu 24.04/GNOME 46 Wayland with synthetic-only filters and verified monitor permission/normal popup delivery.',
    'owned_service': 'Supported-session user-service installation/stop/restart and proof that no existing service is replaced.',
    'provider_grant': 'Operator-owned private environment path and usable worxbend identity/scopes without disclosure or grant replacement.',
    'obs_ownership': 'Authenticated OBS v5, exact OBS AI placeholder scene, matching destination and baseline output ownership/cleanup.',
}
BASE_INPUTS = ('target_configuration', 'target_ownership', 'lcd_observation')
SCENARIO_INPUTS = {
    **{name: BASE_INPUTS for name in SCENARIOS},
    'admin-mobile-feed-reconnect': BASE_INPUTS + ('mobile_https',),
    'synthetic-desktop-to-lcd': BASE_INPUTS + ('supported_desktop', 'owned_service'),
    'measured-cpu-to-lcd': BASE_INPUTS + ('supported_desktop', 'owned_service'),
    'worxbend-obs-live-offline': BASE_INPUTS + ('provider_grant', 'obs_ownership'),
    'lcd-resource-soak': BASE_INPUTS + ('resource_soak',),
}


def preflight(root=ROOT):
    """Inventory only this checkout; do not read credential contents or desktop buses."""
    commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, check=True,
                            capture_output=True, text=True).stdout.strip()
    changed = subprocess.run(['git', 'status', '--porcelain'], cwd=root, check=True,
                             capture_output=True, text=True).stdout.splitlines()
    return {
        'schemaVersion': 1, 'phase': 'preflight-only', 'status': 'blocked',
        'commit': commit, 'dirtyEntryCount': len(changed),
        'toolsAvailable': {tool: shutil.which(tool) is not None for tool in
                           ('docker', 'python3', 'pio', 'dbus-daemon')},
        'localCredentialsHeaderPresent': (root / 'twitch-screen-firmware/src/credentials.h').is_file(),
        'allSoftwareVerified': False, 'productAccepted': False,
        'executionAuthorization': 'scoped PLAN tests after verified prerequisites and resource ownership',
        'actionsPerformed': ['read git identity/status', 'check tool and header presence'],
        'actionsNotPerformed': ['flash', 'service install/enable', 'desktop capture', 'OBS/Twitch mutation'],
        'actionsForbidden': ['private desktop capture', 'unrelated resource changes',
                             'paid events', 'public publication', 'protected AGENTS.md retry'],
        'softwareBlockers': [
            'Default relay DESK negotiation is installed. Four-peer artifact compatibility is tested; default ESP32 advertisement and complete provider/suspend/failure acceptance remain incomplete.',
            'Clock/display/weather/CPU ingress is mounted; stable OAuth routes require valid application configuration independently of ingestion. Bounded scheduled source/reset ownership is installed; complete provider/suspend/source acceptance remains pending. Runtime monitoring APIs are mounted; Admin snapshot/SSE/panel are mounted; complete race/reset/browser and producer-to-panel acceptance remains pending.',
            'Actual container HTTPS proxy is verified; mobile consent and complete documentation/rights audit remain pending.',
        ],
        'physicalLivePrerequisites': [
            'Verified connected board identity, serial path and matching firmware build.',
            'LCD photo/video or explicit human visual observation; 60-minute resource soak.',
            'Operator-supplied private env path and usable worxbend grant, verified without disclosure.',
            'Scoped OBS v5 connection, exact OBS AI placeholder scene and destination/ownership checks.',
            'Supported Ubuntu 24.04 GNOME 46 Wayland session, synthetic-only filters and monitor permission.',
            'Trusted mobile HTTPS/callback and real phone/tablet; verified scoped service/live ownership.',
        ],
        'acceptanceInputs': {key: {'description': value, 'status': 'unverified'}
                             for key, value in INPUTS.items()},
        'missingInputs': ([] if (root / 'twitch-screen-firmware/src/credentials.h').is_file()
                          else ['target_configuration']),
        'scenarios': [{'id': name, 'status': 'blocked', 'evidence': [],
                       'requiredInputs': list(SCENARIO_INPUTS[name]),
                       'nextAction': 'Verify the named inputs and software prerequisites; only then execute and retain real observations.'}
                      for name in SCENARIOS],
    }


def write_report(path, report):
    """Create a private new report, refusing overwrite/symlink targets."""
    path = Path(path)
    if not path.parent.is_dir():
        raise ValueError('output parent must already exist')
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='new private JSON report; parent must exist')
    args = parser.parse_args()
    report = preflight()
    if args.output:
        write_report(args.output, report)
    else:
        print(json.dumps(report, indent=2))
    return 2  # Preflight is not scenario acceptance.


if __name__ == '__main__':
    raise SystemExit(main())
