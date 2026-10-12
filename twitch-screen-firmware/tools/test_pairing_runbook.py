"""Keep the actual pairing launch isolated from unrelated persisted intent."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GUIDE = ROOT / "twitch-screen-firmware/docs/runtime-pairing.md"
CONFIG = ROOT / "twitch-screen-relay/resources/application.conf"


def launch_block():
    blocks = re.findall(r"```sh\n(.*?)```", GUIDE.read_text(), re.S)
    return next(block for block in blocks if "./mill --no-server run" in block)


def launch_environment(scratch):
    """Evaluate the documented assignments without starting a relay or reading state."""
    environment = os.environ.copy()
    environment.update(TMPDIR=str(scratch), RELAY_RUNTIME_STATE_FILE="/unused/existing-state.json")
    command = launch_block().replace("./mill --no-server run", "/usr/bin/env")
    result = subprocess.run(["sh", "-eu", "-c", command], env=environment,
                            capture_output=True, text=True, check=True, timeout=5)
    return dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)


class PairingRunbookTests(unittest.TestCase):
    def test_override_uses_the_production_configuration_name(self):
        names = re.findall(r"settings-file\s*=\s*\$\{\?(\w+)\}", CONFIG.read_text())
        self.assertEqual(names, ["RELAY_RUNTIME_STATE_FILE"])
        self.assertIn(names[0] + "=", launch_block())
        self.assertNotIn("RELAY_RUNTIME_SETTINGS_FILE", launch_block())

    def test_firmware_quickstart_has_no_management_credential_requirement(self):
        readme = (ROOT / "twitch-screen-firmware/README.md").read_text()
        self.assertNotIn("Management authentication is required", readme)
        self.assertIn("network isolation", readme)

    def test_capable_runbook_separates_required_gate_from_fallback(self):
        guide = GUIDE.read_text()
        self.assertIn("--require-desk", guide)
        self.assertIn("device_report_written", guide)
        self.assertIn("socket-written", guide.lower())

    def test_recovery_command_keeps_synthetic_and_physical_boundaries(self):
        guide = GUIDE.read_text()
        self.assertIn('tools/test_runtime_recovery.py', guide)
        self.assertIn('initially granting zero event credit', guide)
        self.assertIn('application_view', guide)
        self.assertIn('production relay accepted reports', guide)
        self.assertIn('Without `--binary`, the runtime regression is skipped', guide)
        self.assertIn('Separate CPU and weather', guide)
        self.assertIn('cpu-recovery-observations.json', guide)
        self.assertIn('weather-recovery-observations.json', guide)

    def test_capable_reconnect_requires_delivery_not_readiness_only(self):
        guide = GUIDE.read_text()
        self.assertIn('--require-desk --desk-reconnect', guide)
        self.assertIn('capableReconnect.passed', guide)
        self.assertIn('tools/test_capable_reconnect.py', guide)
        self.assertIn('Repeated local `desk_ready` messages', guide)
        self.assertIn('empty FIFO', guide)
        result = subprocess.run(['python3', str(ROOT / 'twitch-screen-firmware/tools/pair_runtime.py'),
                                 '--binary', '/unused', '--http-port', '1', '--device-port', '2',
                                 '--evidence', '/unused', '--desk-reconnect'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--desk-reconnect requires --require-desk', result.stderr)

    def test_measurement_diagnostics_fit_the_actual_log_buffer(self):
        source = (ROOT / 'twitch-screen-firmware/src/main.cpp').read_text()
        logger = (ROOT / 'twitch-screen-firmware/src/app_log.h').read_text()
        capacity = int(re.search(r'std::array<char, (\d+)> line', logger).group(1))
        calls = re.findall(r'appLog\(("\[metrics-.*?);', source, re.S)
        self.assertEqual(len(calls), 3)
        for call in calls:
            literals = re.findall(r'"((?:\\.|[^"\\])*)"', call)
            format_string = ''.join(literals).replace(r'\n', '\n')
            worst_case = re.sub(r'%l?u', str(2**32 - 1), format_string)
            self.assertLess(len(worst_case.encode()) + 1, capacity)
        guide = (ROOT / 'twitch-screen-firmware/docs/runtime-measurements.md').read_text()
        self.assertIn('not continuous allocator minima', guide)
        self.assertIn('Linux', guide)
        self.assertIn('exclusive integrator', guide)

    def test_failure_matrix_documents_runtime_not_production_acceptance(self):
        guide = GUIDE.read_text()
        self.assertIn('tools/test_runtime_failures.py', guide)
        self.assertIn('not an installed production relay', guide)
        self.assertIn('five-second budget', guide)
        self.assertIn('original epoch/sequence', guide)
        self.assertIn('Without `--binary`, all actual', guide)
        self.assertIn('Six tests cover seven scenarios', guide)
        self.assertIn('equal-revision content conflicts', guide)
        self.assertIn('Obsolete connection control/barrier records', guide)
        source = (ROOT / 'twitch-screen-firmware/src/main.cpp').read_text()
        self.assertIn('%lu enqueued)', source)
        self.assertNotIn('%lu shown)', source)

    def test_owner_gate_documents_actual_remote_acceptance_boundary(self):
        guide = GUIDE.read_text()
        self.assertIn('tools/test_relay_terminal.py', guide)
        self.assertIn('tools/test_relay_terminal_verdict.py', guide)
        self.assertIn('remote-final-report.hex', guide)
        self.assertIn('not a full relay-suite pass', guide)
        self.assertIn('does not infer remote acceptance of every local write', guide)
        self.assertIn('FIRMWARE_PEER_MAX_READ_BYTES', guide)
        self.assertIn('a blocked kernel writer', guide)
        self.assertTrue((ROOT/'twitch-screen-firmware/tools/RelayTerminalPairing.scala').is_file())

    def test_terminal_expiry_checks_transient_reports_and_actual_reveals(self):
        guide = GUIDE.read_text()
        self.assertIn('Every ready second-session report', guide)
        self.assertIn('both telemetry roots hidden', guide)
        self.assertIn('first-uncover', guide)
        self.assertIn('Missing application or reveal observations fail closed', guide)
        self.assertIn('tools/test_recovery_freshness.py', guide)

    def test_kernel_writer_runbook_preserves_actual_and_pending_boundaries(self):
        guide = GUIDE.read_text()
        for text in ('tools/test_runtime_backpressure.py','tools/test_relay_writers.py',
                     'tools/test_relay_writers_verdict.py','EAGAIN','partial final frame',
                     'remote-report-observations.jsonl','oldest accepted but',
                     'not the entire relay unit suite','FIRMWARE_PEER_SEND_BUFFER_BYTES'):
            self.assertIn(text,guide)
        self.assertIn('default Main/Docker/Compose',guide)
        self.assertIn('exclusive',guide)

    def test_production_fixture_documents_scope_and_requires_ownership(self):
        guide = GUIDE.read_text()
        for text in ('tools/test_production_views.py', 'tools/test_production_views_verdict.py',
                     '--test-owned', 'initially grants', 'zero credit', 'Missing CPU memory remains absent',
                     'not a rollback of a production instance', 'application_telemetry'):
            self.assertIn(text, guide)
        result = subprocess.run(['python3', str(ROOT / 'twitch-screen-firmware/tools/test_production_views.py'),
                                 '--binary', '/unused', '--http-port', '1', '--device-port', '2',
                                 '--evidence', '/unused'], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--test-owned', result.stderr)

    def test_production_reader_and_verdict_bounds_are_documented(self):
        guide = GUIDE.read_text()
        for text in ('8192 UTF-8 bytes', 'before decoding', 'Strict',
                     'covered transitions', 'regressed', 'episode fences'):
            self.assertIn(text, guide)
        for name in ('pair_runtime.py', 'test_four_peer_matrix.py', 'test_runtime_backpressure.py'):
            source = (ROOT / 'twitch-screen-firmware/tools' / name).read_text()
            self.assertIn('from test_production_views import observation_lines', source)
            self.assertIn('in observation_lines(', source)
            self.assertNotRegex(source, r'for line in \w+\.stdout:')

    def test_four_peer_uses_actual_baseline_and_continuous_native_clock(self):
        guide = GUIDE.read_text()
        self.assertIn('tools/test_four_peer_matrix.py', guide)
        self.assertIn('b30b91f4a29bc70a50428ac1bf6894e3ba4b7ac2', guide)
        self.assertIn('old firmware proves queue transfer only', guide)
        source = (ROOT / 'twitch-screen-firmware/test/test_runtime_peer/test_runtime_peer.cpp').read_text()
        self.assertIn('lv_tick_set_cb(lvglNow)', source)
        self.assertNotIn('lv_tick_inc(', source)
        self.assertIn('const uint32_t startedAt = socket.now()', source)
        self.assertIn('controller.weatherRevision()', source)

    def test_restart_and_running_legacy_readers_preserve_ownership_and_scope(self):
        guide = GUIDE.read_text()
        for text in ('tools/test_production_restart.py', 'tools/test_production_restart_verdict.py',
                     'tools/test_legacy_production.py', 'tools/test_legacy_production_verdict.py',
                     'connection IDs', 'runtime revisions restart at one',
                     'not an OS suspend', 'leaves the caller-owned listener running'):
            self.assertIn(text, guide)
        readme = (ROOT / 'twitch-screen-firmware/README.md').read_text()
        self.assertNotIn('The current production relay does not pass that gate', readme)
        self.assertIn('default ESP32 profile', readme)
        for name in ('test_production_restart.py', 'test_legacy_production.py'):
            source = (ROOT / 'twitch-screen-firmware/tools' / name).read_text()
            self.assertIn('from test_production_views import observation_lines', source)
            self.assertIn('--test-owned', source)
            self.assertNotIn('serial.', source)

    def test_process_suspend_reader_requires_actual_owned_children(self):
        guide = GUIDE.read_text()
        for text in ('tools/test_production_suspend.py', 'tools/test_production_suspend_verdict.py',
                     'SIGSTOP', 'SIGCONT', 'waitpid', 'link_down.deskDeadline',
                     'not machine suspend', 'ordinary successor hold/exit bounds',
                     'first uncover', 'before terminating it'):
            self.assertIn(text, guide)
        result = subprocess.run(['python3', str(ROOT / 'twitch-screen-firmware/tools/test_production_suspend.py'),
                                 '--assembly', '/unused', '--binary', '/unused', '--evidence', '/unused'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--test-owned is required', result.stderr)
        source = (ROOT / 'twitch-screen-firmware/tools/test_production_suspend.py').read_text()
        self.assertIn('from test_production_views import observation_lines', source)
        self.assertIn('peer.send_signal(signal.SIGCONT)', source)
        self.assertNotIn('serial.', source)

    def test_suspend_reader_keeps_independent_resume_and_global_card_boundaries(self):
        guide = GUIDE.read_text()
        for text in ('confirmed-stop', 'before `link_down`', 'one chronology',
                     'previous completion', 'requires a fresh run'):
            self.assertIn(text, guide)
        source = (ROOT / 'twitch-screen-firmware/tools/test_production_suspend.py').read_text()
        self.assertIn('resume_device_bounds(records, fixture)', source)
        self.assertIn('verify_callbacks(records, [30000, 100, 100]', source)
        self.assertNotIn('range(boundary + 1, len(records))', source)

    def test_each_launch_uses_a_fresh_private_parent_without_default_state(self):
        base = Path(os.environ["TMPDIR"])
        with tempfile.TemporaryDirectory(prefix="pairing-runbook-test-", dir=base) as temporary:
            scratch = Path(temporary)
            first = Path(launch_environment(scratch)["RELAY_RUNTIME_STATE_FILE"])
            second = Path(launch_environment(scratch)["RELAY_RUNTIME_STATE_FILE"])
            try:
                for path in (first, second):
                    self.assertEqual(path.name, "runtime-state.json")
                    self.assertEqual(path.parent.parent, scratch)
                    self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
                    self.assertFalse(path.exists())
                self.assertNotEqual(first.parent, second.parent)
            finally:
                for path in (first, second):
                    if path.parent.parent == scratch and path.parent.exists():
                        shutil.rmtree(path.parent)


if __name__ == "__main__":
    unittest.main()
