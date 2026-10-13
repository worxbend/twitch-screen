"""Integration contracts are scoped to executable steps, not stray text."""
import re
import shlex
import unittest

try:
    from .test_ci_workflow import job_blocks, run_script, step_blocks
except ImportError:  # unittest discovery imports this file as a top-level module.
    from test_ci_workflow import job_blocks, run_script, step_blocks

ASSEMBLY = 'twitch-screen-relay/out/assembly.dest/out.jar'
NATIVE = 'twitch-screen-firmware/.pio/build/native-runtime-peer/program'
BROWSER = '$TMPDIR/browser/chrome-headless-shell-linux64/chrome-headless-shell'


class IntegrationWorkflowTests(unittest.TestCase):
    def required_step(self, name):
        job = job_blocks()['integration']
        self.assertNotRegex(job, r'(?m)^    (?:if|continue-on-error):')
        matches = [step for step in step_blocks(job) if step.startswith('      - name: ' + name + '\n')]
        self.assertEqual(len(matches), 1, f'exactly one integration step must run: {name}')
        step = matches[0]
        self.assertNotRegex(step, r'(?m)^        (?:if|continue-on-error):')
        script = run_script(step)
        self.assertNotIn('||', script, 'acceptance failures must propagate')
        return script

    def test_immutable_baseline_has_history_in_the_actual_integration_checkout(self):
        job = job_blocks()['integration']
        checkout = [step for step in step_blocks(job) if 'uses: actions/checkout@' in step]
        self.assertEqual(len(checkout), 1)
        self.assertRegex(checkout[0], r'(?m)^          fetch-depth: 0$')

    def test_frozen_installed_producer_browser_modes_are_not_optional(self):
        script = self.required_step('Verify installed producer to real Compose/native/SSE/Chromium')
        self.assertIn('docker build -t twitch-screen-relay:integration twitch-screen-relay', script)
        self.assertIn("IMAGE_ID=$(docker image inspect --format '{{.Id}}' twitch-screen-relay:integration)", script)
        commands = [shlex.split(line) for line in script.splitlines() if 'tools/check_runtime_device.py' in line]
        self.assertEqual(len(commands), 3)
        modes = set()
        for command in commands:
            self.assertEqual(command[:3], ['TSC_TEST_INSTALLED=1', 'PYTHONPATH=', 'twitch-screen-client/.venv/bin/python'])
            for flag, value in (('--image-id', '$IMAGE_ID'), ('--binary', NATIVE), ('--browser-executable', BROWSER)):
                self.assertEqual(command[command.index(flag) + 1], value)
            self.assertIn('--test-owned', command)
            mode = 'denied' if '--deny-monitor' in command else 'malformed' if '--malformed-response' in command else 'normal'
            self.assertFalse('--deny-monitor' in command and '--malformed-response' in command)
            self.assertEqual(command[command.index('--evidence') + 1], '$TMPDIR/producer-' + mode)
            modes.add(mode)
        self.assertEqual(modes, {'normal', 'denied', 'malformed'})

    def test_full_panel_browser_uses_actual_main_and_native(self):
        script = self.required_step('Verify real panel workflows on the final Main and native artifacts')
        commands = [shlex.split(line) for line in script.splitlines() if line.strip()]
        self.assertEqual(len(commands), 1)
        command = commands[0]
        self.assertEqual(command[:2], ['twitch-screen-client/.venv/bin/python', 'twitch-screen-relay/tools/check_admin_runtime.py'])
        for flag, value in (('--assembly', ASSEMBLY), ('--native', NATIVE), ('--browser-executable', BROWSER), ('--evidence', '$TMPDIR/admin-browser')):
            self.assertEqual(command[command.index(flag) + 1], value)
        self.assertIn('--slow-reader', command)

    def test_builds_browser_and_real_process_verdicts_are_mandatory(self):
        build = self.required_step('Build exact native application and production relay')
        self.assertIn('pio test -d twitch-screen-firmware -e native-runtime-peer --without-testing', build)
        self.assertIn('./mill --no-server -j 1 assembly', build)
        baseline = self.required_step('Verify actual Main TCP, canonical SSE and chronological baseline')
        for script in ('check_admin_runtime.py', 'test_production_restart.py', 'test_production_restart_verdict.py', 'check_runtime_admin.py'):
            self.assertRegex(baseline, r'(?m)^[^#\n]*\b' + re.escape(script) + r'\b')
        suspend = self.required_step('Verify real process-gap freshness and global card chronology')
        for script in ('test_production_suspend.py', 'test_production_suspend_verdict.py'):
            self.assertRegex(suspend, r'(?m)^[^#\n]*\b' + re.escape(script) + r'\b')
        browser = self.required_step('Prepare pinned test-only Chromium for authority races')
        self.assertIn('--require-hashes --only-binary=:all: -r site/requirements-browser.lock', browser)
        self.assertIn('python site/tools/install_browser.py --output "$TMPDIR/browser"', browser)
        authority = self.required_step('Verify real browser epoch races and local source/report expiry')
        self.assertIn('tools/check_admin_authority.py', authority)
        names = [step.splitlines()[0] for step in step_blocks(job_blocks()['integration'])]
        self.assertLess(names.index('      - name: Build exact native application and production relay'),
                        names.index('      - name: Verify actual Main TCP, canonical SSE and chronological baseline'))
        self.assertLess(names.index('      - name: Prepare pinned test-only Chromium for authority races'),
                        names.index('      - name: Verify real browser epoch races and local source/report expiry'))

    def test_failure_diagnostics_are_retained_without_uploading_runtime_scratch(self):
        uploads = [step for step in step_blocks(job_blocks()['integration']) if 'uses: actions/upload-artifact@' in step]
        self.assertEqual(len(uploads), 1)
        upload = uploads[0]
        self.assertIn('if: ${{ always() }}', upload)
        self.assertIn('retention-days: 7', upload)
        self.assertIn('include-hidden-files: false', upload)
        self.assertIn('if-no-files-found: warn', upload)
        paths = re.findall(r'(?m)^            (\$\{\{ runner.temp \}\}/[^\n]+)$', upload)
        self.assertEqual(set(paths), {
            '${{ runner.temp }}/twitch-screen-integration/admin-main/result.json',
            '${{ runner.temp }}/twitch-screen-integration/admin-main/test_legacy_production.py.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-main/test_legacy_production_verdict.py.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-main/legacy-running/build.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-main/legacy-running/peer-stderr.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-browser/result.json',
            '${{ runner.temp }}/twitch-screen-integration/admin-browser/test_legacy_production.py.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-browser/test_legacy_production_verdict.py.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-browser/legacy-running/build.log',
            '${{ runner.temp }}/twitch-screen-integration/admin-browser/legacy-running/peer-stderr.log',
        })
        self.assertNotIn('*', upload)


if __name__ == '__main__':
    unittest.main()
