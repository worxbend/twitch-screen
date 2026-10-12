"""Current reader examples must match the credential-free browser boundary."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DocumentationBoundaryTests(unittest.TestCase):
    def test_current_api_examples_do_not_require_management_credentials(self):
        text = (ROOT / 'docs/reference/http-api.md').read_text()
        self.assertNotIn('Authorization: Bearer', text)
        self.assertNotIn('curl --user', text)
        self.assertNotIn('Use the same token', text)
        self.assertIn('Authorization headers confer no privilege', text)
        self.assertIn('not mounted', text)

    def test_current_setup_configuration_and_ci_follow_verified_boundary(self):
        setup = (ROOT / 'docs/guides/relay-setup.md').read_text()
        self.assertNotIn('export RELAY_HTTP_AUTH_API_TOKEN=', setup)
        self.assertNotIn('RELAY_HTTP_AUTH_API_TOKEN=', setup)
        self.assertNotIn('Authorization: Bearer', setup)
        self.assertNotIn('compose.live.yaml', setup)
        self.assertNotIn('publish on all host interfaces by default', setup)
        self.assertIn('Both native listeners and Compose port publication default to loopback', setup)
        self.assertIn('python3 tools/compose.py --env-file .env.live up', setup)
        self.assertIn('Runtime monitoring APIs are mounted', setup)
        configuration = (ROOT / 'docs/reference/relay-configuration.md').read_text()
        self.assertNotIn('At least one complete authentication method is mandatory', configuration)
        self.assertIn('RELAY_HTTP_PUBLIC_ORIGIN', configuration)
        self.assertIn('RELAY_HTTP_TRUSTED_PROXIES', configuration)
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('pio test -e native-lvgl', workflow)
        self.assertIn('pio test -e native-lvgl-base', workflow)
        self.assertIn('test ! -e src/credentials.h && cp', workflow)

    def test_simulated_tutorial_does_not_require_management_credentials(self):
        text = (ROOT / 'docs/guides/first-simulated-stream.md').read_text()
        self.assertNotIn('Missing management credentials are a startup error', text)
        self.assertIn('No management credentials are required', text)

    def test_current_ingress_status_and_hold_migration_match_source(self):
        api = (ROOT / 'docs/reference/http-api.md').read_text()
        migration = (ROOT / 'docs/guides/migration.md').read_text()
        configuration = (ROOT / 'docs/reference/relay-configuration.md').read_text()
        self.assertIn('## Mounted desk ingress', api)
        self.assertIn('100–30000 ms', api)
        self.assertNotIn('Integer 1–6553500', api)
        self.assertNotIn('Clock, display, CPU, weather, runtime monitoring, admin snapshot and admin events are not mounted', api)
        self.assertIn('clock/CPU/notification APIs', migration)
        self.assertNotIn('APIs absent from this relay', migration)
        self.assertIn('HTTP POST now always defaults to 8000 ms', configuration)

    def test_weather_http_status_is_current(self):
        api = (ROOT / 'docs/reference/http-api.md').read_text()
        self.assertIn('`GET /weather`', api)
        self.assertIn('`PUT /weather`', api)
        self.assertNotIn('Weather, runtime monitoring, admin snapshot and admin events are not mounted.', api)
        runner = (ROOT / 'tools/e2e_device.py').read_text()
        self.assertIn('Clock/display/weather/CPU ingress is mounted', runner)
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_runtime_recovery.py --binary', ci)

    def test_reconnect_ci_exercises_the_actual_application(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_capable_reconnect.py --binary .pio/build/native-runtime-peer/program', workflow)

    def test_h_runbook_separates_mounted_ingress_from_pending_profile(self):
        guide = (ROOT / 'docs/guides/end-to-end-testing.md').read_text()
        self.assertIn('Clock/display/weather/CPU HTTP ingress is mounted', guide)
        self.assertIn('internal-gated actual hub/session', guide)
        self.assertIn('same-port', guide)
        self.assertNotIn('Mount clock/display/weather/CPU/runtime-monitoring APIs', guide)

    def test_root_feature_matrix_matches_mounted_weather_and_gated_sessions(self):
        text = (ROOT / 'README.md').read_text()
        self.assertIn('Clock/display/weather/CPU HTTP APIs', text)
        self.assertIn('Actual internal-gated hub/session', text)
        self.assertNotIn('Weather API, runtime channel switching', text)

    def test_oauth_docs_follow_stable_application_owner(self):
        api = (ROOT / 'docs/reference/http-api.md').read_text()
        setup = (ROOT / 'docs/guides/twitch-app-setup.md').read_text()
        self.assertNotIn('exist only in live mode', api)
        self.assertNotIn('Disabled/simulated modes do not mount', api)
        self.assertIn('valid OAuth application configuration', api)
        self.assertNotIn('RELAY_HTTP_AUTH_BASIC_USERNAME=', setup)
        self.assertNotIn('--user admin', setup)
        self.assertNotIn('Management authentication is required', setup)
        self.assertNotIn('endpoints exist only in `live` mode', setup)
        self.assertIn('independent of ingestion mode', setup)
        self.assertIn('Expose only', setup)
        migration = (ROOT / 'docs/guides/migration.md').read_text()
        self.assertNotIn('Neither peer currently implements complete capable synchronization', migration)
        self.assertIn('internal-gated', migration)

    def test_runtime_failure_ci_exercises_the_actual_application(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_runtime_failures.py --binary .pio/build/native-runtime-peer/program', workflow)

    def test_h_status_distinguishes_installed_recovery_from_pending_acceptance(self):
        guide = (ROOT / 'docs/guides/end-to-end-testing.md').read_text()
        runner = (ROOT / 'tools/e2e_device.py').read_text()
        self.assertIn('Scheduled clock-discontinuity recovery', guide)
        self.assertIn('bounded continuing retry', guide)
        self.assertNotIn('Finish that coordinator', guide)
        self.assertIn('scheduled source/reset ownership is installed', runner)
        self.assertNotIn('Automatic coordinated source/reset ownership, runtime-monitoring APIs and admin SSE/panel are absent', runner)
        self.assertIn('real provider/suspend', guide)
        self.assertIn('admin snapshot/SSE/panel are mounted', guide)

    def test_freshness_verdict_ci_uses_actual_recovery_observations(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_runtime_recovery.py --binary .pio/build/native-runtime-peer/program --evidence', workflow)
        self.assertIn('python tools/test_recovery_freshness.py --evidence', workflow)
        self.assertIn('${{ runner.temp }}/firmware-recovery', workflow)

    def test_production_relay_status_does_not_repeat_classic_only_claims(self):
        api = (ROOT / 'docs/reference/http-api.md').read_text()
        migration = (ROOT / 'docs/guides/migration.md').read_text()
        guide = (ROOT / 'docs/guides/end-to-end-testing.md').read_text()
        runner = (ROOT / 'tools/e2e_device.py').read_text()
        self.assertNotIn('current production TCP still negotiates classic only', api)
        self.assertNotIn('Runtime monitoring, admin snapshot and admin events are not mounted', api)
        self.assertIn('`GET /twitch/monitoring`', api)
        self.assertIn('`PATCH /twitch/monitoring`', api)
        self.assertIn('default production relay', migration)
        self.assertIn('default ESP32 enables DESK', migration)
        self.assertNotIn('Default production peers still keep CAP_DESK_V1 disabled', migration)
        self.assertNotIn('Runtime monitoring and private admin snapshot/SSE/panel remain absent', guide)
        self.assertIn('admin snapshot/SSE/panel are mounted', guide)
        self.assertNotIn('private admin snapshot/SSE/panel remain absent', guide)
        self.assertIn('Runtime monitoring APIs are mounted', runner)
        self.assertNotIn('Runtime-monitoring APIs and admin SSE/panel remain absent', runner)

    def test_current_peer_matrix_copy_keeps_target_acceptance_separate(self):
        guide = (ROOT / 'docs/guides/end-to-end-testing.md').read_text()
        runner = (ROOT / 'tools/e2e_device.py').read_text()
        self.assertIn('all four baseline/current peer combinations', guide)
        self.assertIn('exact extracted Compose assembly', guide)
        self.assertIn('not default ESP32 or LCD acceptance', guide)
        self.assertIn('Four-peer artifact compatibility is tested', runner)

    def test_non_twitch_template_is_safe_and_used_by_compose_smoke(self):
        example = ROOT / 'twitch-screen-relay/.env.example'
        self.assertTrue(example.is_file(), 'The safe non-Twitch template is missing')
        values = dict(line.split('=', 1) for line in example.read_text().splitlines()
                      if line and not line.startswith('#'))
        self.assertEqual(values['RELAY_TWITCH_MODE'], 'disabled')
        self.assertEqual(values['RELAY_TWITCH_CHANNEL'], '')
        self.assertEqual(values['RELAY_CHAT_NOTIFICATIONS'], 'hide')
        self.assertEqual(values['RELAY_BIND_IP'], '127.0.0.1')
        self.assertFalse(any('AUTH_' in key or 'SECRET' in key or 'TOKEN' in key for key in values))
        smoke = (ROOT / 'twitch-screen-relay/tools/smoke_compose.py').read_text()
        self.assertIn('"--env-file", ".env.example"', smoke)
        setup = (ROOT / 'docs/guides/relay-setup.md').read_text()
        self.assertIn('python3 tools/compose.py --env-file .env.example up --build -d', setup)

    def test_observer_ci_exercises_preallocation_boundary(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_production_observer.py -v', workflow)

    def test_lifecycle_ci_exercises_shared_original_identity_validator(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('python tools/test_runtime_lifecycle.py', workflow)

    def test_admin_readers_and_ci_match_mounted_software_not_product_acceptance(self):
        api = (ROOT / 'docs/reference/http-api.md').read_text()
        self.assertIn('`GET /admin/snapshot`', api)
        self.assertIn('`GET /admin/events`', api)
        self.assertNotIn('Admin snapshot and admin events are not mounted', api)
        self.assertIn('unknown FIFO is null', api)
        root = (ROOT / 'README.md').read_text()
        self.assertNotIn('| Reported admin queues, private admin panel/feed | Pending production implementation |', root)
        runner = (ROOT / 'tools/e2e_device.py').read_text()
        self.assertNotIn('admin SSE/panel remain absent', runner)
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('test_runtime_observations.py', workflow)
        self.assertIn('test_production_restart.py --assembly', workflow)
        self.assertIn('check_admin_runtime.py --assembly', workflow)

    def test_ci_exercises_repaired_epoch_expiry_and_suspend_boundaries(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('node --test twitch-screen-relay/tools/tests/panel_authority.cjs', workflow)
        self.assertIn('check_admin_authority.py --assembly', workflow)
        self.assertIn('test_production_suspend.py --assembly', workflow)
        self.assertIn('test_production_suspend_verdict.py --evidence', workflow)
        self.assertIn('site/requirements-browser.lock', workflow)
        self.assertIn('python site/tools/install_browser.py --output', workflow)

    def test_bodyless_post_examples_supply_json_media_type(self):
        text = (ROOT / 'docs/reference/http-api.md').read_text()
        commands = re.findall(r'curl[^`]+?--request POST[^`]+?(?=\n\n|\n```)', text)
        self.assertEqual(len(commands), 3)
        for command in commands:
            self.assertIn("-H 'Content-Type: application/json'", command)

    def test_architecture_and_device_instructions_match_current_auth(self):
        architecture = (ROOT / 'docs/reference/architecture.md').read_text()
        self.assertNotIn('Management endpoints require configured Basic', architecture)
        self.assertNotIn('HTTP management authentication protects', architecture)
        self.assertIn('trusted HTTPS/proxy and mobile acceptance remain pending', architecture)
        device = (ROOT / 'docs/guides/device-use.md').read_text()
        self.assertNotIn('Authorization: Bearer', device)
        self.assertNotIn('Basic-auth installations can use', device)
        self.assertIn('Anyone reaching HTTP can control', device)


if __name__ == '__main__':
    unittest.main()
