# SPDX-License-Identifier: MIT
"""Source checks for the prepared workflows; these do not execute GitHub Actions."""
import ast
import fnmatch
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from build import build
from verify_artifact import verify

ROOT = Path(__file__).resolve().parents[2]


class ArtifactWorkflowTests(unittest.TestCase):
    def test_artifact_readback_detects_tampering_and_private_member(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-artifact-test-") as directory:
            root = Path(directory)
            build(root)
            self.assertEqual(verify(root)["status"], "passed")
            (root / "index.html").write_text("changed after build")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                verify(root)
            build(root)
            (root / ".env").write_text("forbidden test fixture")
            with self.assertRaisesRegex(ValueError, "allowlist"):
                verify(root)

    def test_artifact_provenance_is_checked_even_with_consistent_hashes(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-provenance-test-") as directory:
            root = Path(directory)
            build(root)
            name = "assets/provenance.json"
            provenance = json.loads((root / name).read_text())
            provenance["assets"][0]["physicalEvidence"] = True
            data = json.dumps(provenance).encode()
            (root / name).write_bytes(data)
            manifest_path = root / "build-manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["files"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            manifest["gzipBytes"] = sum(len(gzip.compress((root / member).read_bytes(), mtime=0))
                                        for member in manifest["files"])
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "physical"):
                verify(root)

    def test_docs_and_workflow_changes_trigger_artifact_checks(self):
        text = (ROOT / ".github/workflows/pages-build.yml").read_text()
        for section in text.split("paths: ")[1:]:
            paths = section.splitlines()[0]
            for target in ("docs/**", "README.md", "LICENSE.md", "THIRD_PARTY_NOTICES.md",
                           ".github/workflows/pages-*.yml"):
                self.assertIn(target, paths)

    def test_all_linked_repository_inputs_trigger_artifact_checks(self):
        text = (ROOT / '.github/workflows/pages-build.yml').read_text()
        features = json.loads((ROOT / 'site/content/features.json').read_text())
        dependencies = {item['reference'] for item in features}
        source = (ROOT / 'site/index.html').read_text()
        dependencies.update(re.findall(
            r'https://github\.com/worxbend/twitch-screen/(?:blob|tree)/main/([A-Za-z0-9_./-]+)',
            source))
        self.assertTrue(dependencies)
        # Both push and PR filters must react to deletion/anchor/claim-source edits.
        for section in text.split('paths: ')[1:]:
            patterns = ast.literal_eval(section.splitlines()[0])
            for dependency in sorted(dependencies):
                with self.subTest(dependency=dependency):
                    self.assertTrue(any(fnmatch.fnmatchcase(dependency, pattern)
                                        or fnmatch.fnmatchcase(dependency + '/', pattern)
                                        for pattern in patterns),
                                    f'linked build input cannot trigger CI: {dependency}')

    def test_all_action_refs_are_immutable_and_checkouts_do_not_persist_credentials(self):
        for name in ("pages-build.yml", "pages-deploy.yml"):
            text = (ROOT / ".github/workflows" / name).read_text()
            refs = re.findall(r"uses:\s*(\S+)", text)
            self.assertTrue(refs)
            for ref in refs:
                self.assertRegex(ref, r"^actions/[a-z-]+@[0-9a-f]{40}$")
            self.assertIn("persist-credentials: false", text)
            self.assertIn("python-version: '3.12.12'", text)
            self.assertIn("--require-hashes --only-binary=:all:", text)
            self.assertIn("python site/tests/browser_check.py", text)
            self.assertIn("python site/tools/install_browser.py", text)
            self.assertIn("python -m playwright install-deps chromium", text)
            self.assertNotIn("playwright install --with-deps", text)
            self.assertIn("python site/tools/verify_artifact.py site/dist", text)
            self.assertNotIn("pull_request_target", text)
            self.assertNotIn("secrets.", text)

    def test_exact_artifact_browser_gate_runs_after_build_before_upload(self):
        for name, upload in (('pages-build.yml', 'actions/upload-artifact@'),
                             ('pages-deploy.yml', 'actions/upload-pages-artifact@')):
            text = (ROOT / '.github/workflows' / name).read_text()
            with self.subTest(workflow=name):
                build_position = text.index('python site/tools/build.py')
                verify_position = text.index('python site/tools/verify_artifact.py site/dist')
                browser_position = text.index('python site/tests/browser_check.py --artifact site/dist')
                self.assertLess(build_position, verify_position)
                self.assertLess(verify_position, browser_position)
                self.assertLess(browser_position, text.index(upload))
                between = text[browser_position:text.index(upload)]
                self.assertNotIn('build.py', between)
                self.assertNotIn('continue-on-error', text)

    def test_build_has_no_publication_permission_or_action(self):
        text = (ROOT / ".github/workflows/pages-build.yml").read_text()
        self.assertIn("contents: read", text)
        self.assertNotIn("pages: write", text)
        self.assertNotIn("id-token: write", text)
        self.assertNotIn("deploy-pages", text)
        self.assertIn("include-hidden-files: true", text)

    def test_deploy_manual_confirmation_and_job_only_write_permissions(self):
        text = (ROOT / ".github/workflows/pages-deploy.yml").read_text()
        on = text.split("on:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("workflow_dispatch:", on)
        for trigger in ("push:", "pull_request:", "workflow_run:", "schedule:"):
            self.assertNotIn(trigger, on)
        self.assertIn("default: do-not-publish", on)
        self.assertIn("inputs.confirmation == 'publish'", text)
        self.assertIn("github.ref == 'refs/heads/main'", text)
        self.assertIn("github.event_name == 'workflow_dispatch'", text)
        build_job, deploy_job = text.split("\n  deploy:\n", 1)
        self.assertNotIn("pages: write", build_job)
        self.assertNotIn("id-token: write", build_job)
        self.assertIn("ref: ${{ github.sha }}", build_job)
        self.assertIn("needs: build", deploy_job)
        self.assertIn("pages: write", deploy_job)
        self.assertIn("id-token: write", deploy_job)
        self.assertIn("name: github-pages", deploy_job)
        self.assertNotIn("configure-pages", text)


if __name__ == "__main__":
    unittest.main()
