# SPDX-License-Identifier: MIT
"""Exercise the documented preview command as an owned child process."""
import hashlib
import os
from pathlib import Path
import selectors
import signal
import socket
import subprocess
import sys
import tempfile
import unittest
import zipfile
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


sys.path.insert(0, str(ROOT / 'site/tools'))
from build import build


class PreviewCliTests(unittest.TestCase):
    def test_invalid_port_cli_fails_before_build_or_listener(self):
        for port in ('-1', '65536', '999999999999'):
            with self.subTest(port=port), tempfile.TemporaryDirectory(prefix='site-cli-port-') as directory:
                result = subprocess.run(
                    [sys.executable, str(ROOT / 'site/tools/preview.py'), '--port', port],
                    cwd=ROOT, env=dict(os.environ, TMPDIR=directory),
                    capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 1)
                self.assertIn('port must be an integer from 0 to 65535', result.stderr)
                self.assertNotIn('Traceback', result.stderr)
                self.assertNotIn('Preview:', result.stdout)
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_invalid_artifact_cli_fails_before_listener_and_cleans_snapshot(self):
        with tempfile.TemporaryDirectory(prefix='site-cli-invalid-') as directory:
            artifact = Path(directory) / 'artifact'
            build(artifact)
            (artifact / 'index.html').write_text('tampered')
            scratch = Path(directory) / 'scratch'
            scratch.mkdir()
            result = subprocess.run(
                [sys.executable, str(ROOT / 'site/tools/preview.py'),
                 '--artifact', str(artifact), '--port', '0'],
                cwd=ROOT, env=dict(os.environ, TMPDIR=str(scratch)),
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertIn('preview failed:', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertNotIn('Preview:', result.stdout)
            self.assertEqual(list(scratch.iterdir()), [])

    def test_root_and_project_preview_sigint_releases_port_and_temporary_build(self):
        for base, input_mode in ((base, mode) for base in ('/', '/twitch-screen/')
                                 for mode in ('source', 'artifact', 'archive', 'artifact-pinned', 'archive-pinned')):
            with self.subTest(base=base, mode=input_mode), tempfile.TemporaryDirectory(prefix="site-cli-test-") as directory:
                artifact = Path(directory) / 'artifact'
                artifact_mode = input_mode != 'source'
                if artifact_mode:
                    build(artifact, base)
                    arguments = ['--artifact', str(artifact)]
                    expected_html = (artifact / 'index.html').read_bytes()
                    if input_mode.startswith('archive'):
                        archive = Path(directory) / 'site.zip'
                        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
                            for member in artifact.rglob('*'):
                                if member.is_file():
                                    bundle.write(member, member.relative_to(artifact).as_posix())
                        arguments = ['--archive', str(archive)]
                    if input_mode.endswith('-pinned'):
                        digest = hashlib.sha256((artifact / 'build-manifest.json').read_bytes()).hexdigest()
                        arguments += ['--expected-manifest-sha256', digest]
                else:
                    arguments = ['--base-path', base]
                    expected_html = None
                scratch = Path(directory) / "scratch"
                scratch.mkdir()
                environment = dict(os.environ, TMPDIR=str(scratch))
                with (Path(directory) / "stderr.log").open("w+") as errors:
                    process = subprocess.Popen(
                        [sys.executable, str(ROOT / "site/tools/preview.py"),
                         *arguments, "--port", "0"],
                        cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                        stderr=errors, text=True)
                    assert process.stdout is not None
                    try:
                        with selectors.DefaultSelector() as ready:
                            ready.register(process.stdout, selectors.EVENT_READ)
                            self.assertTrue(ready.select(timeout=10), "preview did not announce readiness")
                            line = process.stdout.readline().strip()
                        self.assertTrue(line.startswith("Preview: http://127.0.0.1:"), line)
                        url = line.removeprefix("Preview: ")
                        address = urlsplit(url)
                        self.assertEqual(address.hostname, "127.0.0.1")
                        self.assertEqual(address.path, base)
                        with urlopen(url, timeout=3) as response:
                            body = response.read()
                            self.assertIn(b"A small display for your whole desk.", body)
                            if artifact_mode:
                                self.assertEqual(body, expected_html)
                                self.assertEqual((artifact / 'index.html').read_bytes(), expected_html)
                            self.assertIn("connect-src 'none'", response.headers["Content-Security-Policy"])
                            self.assertEqual(response.headers["Cache-Control"], "no-store")
                        with urlopen(Request(url + "assets/orbit.svg", method="HEAD"), timeout=3) as response:
                            self.assertIn("image/svg+xml", response.headers["Content-Type"])
                            self.assertEqual(response.read(), b"")
                        if base != "/":
                            with urlopen(url.rstrip("/"), timeout=3) as response:
                                self.assertEqual(response.url, url)
                        with self.assertRaises(HTTPError) as rejected:
                            urlopen(url + ".env", timeout=3)
                        self.assertEqual(rejected.exception.code, 404)
                        rejected.exception.close()
                        self.assertEqual(len(list(scratch.iterdir())), 2 if input_mode.startswith('archive') else 1,
                                         "preview must own only its admitted temporary snapshots")
                        process.send_signal(signal.SIGINT)
                        process.communicate(timeout=5)
                        self.assertEqual(process.returncode, 0)
                        self.assertEqual(list(scratch.iterdir()), [], "temporary build leaked")
                        with socket.socket() as probe:
                            probe.settimeout(1)
                            self.assertNotEqual(probe.connect_ex((address.hostname, address.port)), 0,
                                                "preview listener leaked")
                        errors.seek(0)
                        self.assertNotIn("Traceback", errors.read())
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.communicate(timeout=5)
                        process.stdout.close()


if __name__ == "__main__":
    unittest.main()
