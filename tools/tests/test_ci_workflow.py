"""Stdlib CI contracts; actionlint remains the YAML/expression validator."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/ci.yml'
REQUIRED_JOBS = {'relay', 'protocol', 'firmware', 'companion', 'integration', 'container'}


def job_blocks(workflow=None):
    text = WORKFLOW.read_text() if workflow is None else workflow
    jobs = text.split('\njobs:\n', 1)[1]
    matches = list(re.finditer(r'^  ([a-z][a-z0-9_-]*):\s*$', jobs, re.MULTILINE))
    return {match[1]: jobs[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(jobs)]
            for index, match in enumerate(matches)}


def step_blocks(job):
    matches = list(re.finditer(r'^      - ', job, re.MULTILINE))
    return [job[match.start():matches[index + 1].start() if index + 1 < len(matches) else len(job)]
            for index, match in enumerate(matches)]


def run_script(step):
    match = re.search(r'^      (?:- )?  run: (.*)$|^      - run: (.*)$', step, re.MULTILINE)
    if match is None:
        return ''
    value = match[1] if match[1] is not None else match[2]
    if value != '|':
        return value
    lines = []
    for line in step[match.end():].splitlines()[1:]:
        if line and not line.startswith('          '):
            break
        lines.append(line[10:])
    return '\n'.join(lines)


class CiWorkflowTests(unittest.TestCase):
    def test_verification_is_read_only_pinned_and_fail_fast(self):
        workflow = WORKFLOW.read_text()
        self.assertRegex(workflow, r'(?m)^permissions:\n  contents: read$')
        self.assertRegex(workflow, r'(?m)^defaults:\n  run:\n    shell: bash$')
        self.assertNotIn('pull_request_target:', workflow)
        self.assertNotRegex(workflow, r'(?m)^\s*(?:contents|packages|pages|id-token): write$')
        self.assertNotIn('secrets.', workflow)
        self.assertNotIn('deploy-pages@', workflow)
        self.assertNotIn('action-gh-release@', workflow)
        for name, job in job_blocks().items():
            with self.subTest(job=name):
                self.assertRegex(job, r'(?m)^    timeout-minutes: [1-9][0-9]*$')
                self.assertNotRegex(job, r'(?m)^\s+continue-on-error:')
                if name != 'required':
                    self.assertNotRegex(job, r'(?m)^    if:')
                for step in step_blocks(job):
                    uses = re.search(r'(?m)^\s+(?:- )?uses: ([^\s#]+)', step)
                    if uses:
                        self.assertRegex(uses[1], r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$')
                        if uses[1].startswith('actions/checkout@'):
                            self.assertIn('persist-credentials: false', step)
                    self.assertNotIn('${{', run_script(step), 'workflow data must enter shell via env')

    def test_protocol_panel_expiry_and_epoch_checks_cannot_be_softened(self):
        steps = step_blocks(job_blocks()['protocol'])
        panel = [step for step in steps if 'node --test twitch-screen-relay/tools/tests/panel_authority.cjs' in run_script(step)]
        self.assertEqual(len(panel), 1)
        self.assertIn('node twitch-screen-relay/tools/tests/panel_epoch.cjs', run_script(panel[0]))
        self.assertNotRegex(panel[0], r'(?m)^\s+(?:if|continue-on-error):')
        self.assertNotIn('||', run_script(panel[0]))

    def gate_script(self):
        jobs = job_blocks()
        self.assertIn('required', jobs, 'a stable fail-closed aggregate check is required')
        gate = jobs['required']
        self.assertRegex(gate, r'(?m)^    if: \$\{\{ always\(\) \}\}$')
        needs = re.search(r'(?m)^    needs: \[(.*)\]$', gate)
        self.assertIsNotNone(needs)
        assert needs is not None
        self.assertEqual({name.strip() for name in needs[1].split(',')}, set(jobs) - {'required'})
        self.assertEqual(set(jobs) - {'required'}, REQUIRED_JOBS)
        steps = step_blocks(gate)
        self.assertEqual(len(steps), 1)
        self.assertIn("NEEDS_JSON: ${{ toJSON(needs) }}", steps[0])
        self.assertNotRegex(gate, r'(?m)^\s+continue-on-error:')
        return run_script(steps[0])

    def run_gate(self, payload):
        env = {'PATH': os.environ['PATH'], 'NEEDS_JSON': payload}
        return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', self.gate_script()],
                              env=env, cwd=ROOT, capture_output=True, text=True, timeout=10)

    def test_aggregate_accepts_only_all_required_successes(self):
        payload = {name: {'result': 'success'} for name in REQUIRED_JOBS}
        result = self.run_gate(json.dumps(payload))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_aggregate_fails_for_every_failed_skipped_or_cancelled_dependency(self):
        for name in sorted(REQUIRED_JOBS):
            for status in ('failure', 'skipped', 'cancelled', '', None):
                with self.subTest(job=name, status=status):
                    payload: dict[str, dict[str, str | None]] = {
                        job: {'result': 'success'} for job in REQUIRED_JOBS}
                    payload[name] = {'result': status}
                    self.assertNotEqual(self.run_gate(json.dumps(payload)).returncode, 0)

    def test_aggregate_fails_closed_for_missing_unknown_or_malformed_results(self):
        successes = {name: {'result': 'success'} for name in REQUIRED_JOBS}
        invalid = ['', 'null', '[]', '{}', '{', json.dumps({**successes, 'unexpected': {'result': 'success'}})]
        invalid += [json.dumps({job: row for job, row in successes.items() if job != name})
                    for name in REQUIRED_JOBS]
        invalid += [json.dumps({**successes, 'relay': value}) for value in ({}, 'success', None)]
        for payload in invalid:
            with self.subTest(payload=payload):
                self.assertNotEqual(self.run_gate(payload).returncode, 0)

    def test_relay_executes_fresh_forked_tests_instead_of_cached_alias(self):
        job = job_blocks()['relay']
        commands = [run_script(step) for step in step_blocks(job)]
        self.assertIn('./mill --no-server -j 1 test.testForked', commands)
        self.assertNotIn('./mill --no-server -j 1 test', commands)
        step = next(step for step in step_blocks(job) if 'test.testForked' in run_script(step))
        self.assertNotRegex(step, r'(?m)^\s+(?:if|continue-on-error):')
        self.assertNotIn('twitch-screen-relay/out', job)

    def test_scratch_preparation_exports_an_existing_job_isolated_directory(self):
        jobs = job_blocks()
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            runner_temp = Path(temporary)
            paths = set()
            for name in ('relay', 'protocol', 'firmware'):
                with self.subTest(job=name):
                    step = next(step for step in step_blocks(jobs[name])
                                if "printf 'TMPDIR=%s\\n'" in run_script(step))
                    github_env = runner_temp / (name + '.env')
                    result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', run_script(step)],
                                            env={'PATH': os.environ['PATH'], 'RUNNER_TEMP': temporary,
                                                 'GITHUB_ENV': str(github_env)},
                                            cwd=ROOT, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    exported = github_env.read_text().splitlines()
                    self.assertEqual(len(exported), 1)
                    key, value = exported[0].split('=', 1)
                    self.assertEqual(key, 'TMPDIR')
                    scratch = Path(value)
                    self.assertEqual(scratch.parent, runner_temp)
                    self.assertTrue(scratch.is_dir())
                    paths.add(scratch)
            self.assertEqual(len(paths), 3, 'jobs must not share scratch state')

    def test_tmpdir_is_created_and_exported_before_relay_and_firmware_tests(self):
        jobs = job_blocks()
        for name in ('relay', 'protocol', 'firmware', 'companion', 'integration'):
            with self.subTest(job=name):
                steps = step_blocks(jobs[name])
                prepare = next((index for index, step in enumerate(steps)
                                if "printf 'TMPDIR=%s\\n'" in run_script(step)), None)
                self.assertIsNotNone(prepare, f'{name} needs an explicit TMPDIR')
                assert prepare is not None
                script = run_script(steps[prepare])
                self.assertIn('mkdir -p "$RUNNER_TEMP/twitch-screen-', script)
                self.assertIn('>> "$GITHUB_ENV"', script)
                for index, step in enumerate(steps):
                    if re.search(r'(?:unittest|pio test|\./mill .*test)', run_script(step)):
                        self.assertGreater(index, prepare)
                self.assertNotRegex(steps[prepare], r'(?m)^\s+(?:if|continue-on-error):')


if __name__ == '__main__':
    unittest.main()
