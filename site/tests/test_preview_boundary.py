# SPDX-License-Identifier: MIT
"""Actual loopback requests must not expand the static preview's public boundary."""
from http.client import HTTPConnection
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from build import build
from preview import server


class PreviewBoundaryTests(unittest.TestCase):
    def test_exact_authority_and_public_routes(self):
        for base in ('/', '/twitch-screen/'):
            with self.subTest(base=base), tempfile.TemporaryDirectory(prefix='ts-preview-boundary-') as directory:
                build(directory, base)
                (Path(directory) / 'private.txt').write_text('synthetic private sentinel')
                with server(directory, base) as http:
                    worker = threading.Thread(target=http.serve_forever)
                    worker.start()
                    authority = f'127.0.0.1:{http.server_port}'
                    try:
                        def request(path, hosts, method='GET'):
                            connection = HTTPConnection('127.0.0.1', http.server_port, timeout=3)
                            try:
                                connection.putrequest(method, path, skip_host=True)
                                for host in hosts:
                                    connection.putheader('Host', host)
                                connection.endheaders()
                                response = connection.getresponse()
                                return response.status, response.read(), response.getheaders()
                            finally:
                                connection.close()

                        for hosts in ([], ['attacker.invalid'], [authority, authority],
                                      ['localhost:' + str(http.server_port)], ['127.0.0.1:1']):
                            with self.subTest(hosts=hosts):
                                status, body, _ = request(base, hosts)
                                self.assertEqual(status, 421)
                                self.assertNotIn(b'A small display', body)
                        for path in (base + 'private.txt', base + 'assets/../index.html',
                                     base + '%69ndex.html', base + './index.html',
                                     base + 'assets//orbit.svg',
                                     'http://' + authority + base, 'http://[malformed'):
                            with self.subTest(path=path):
                                status, body, _ = request(path, [authority])
                                self.assertEqual(status, 404)
                                self.assertNotIn(b'synthetic private sentinel', body)
                        for name in ('', 'index.html', 'assets/orbit.svg', 'build-manifest.json'):
                            for method in ('GET', 'HEAD'):
                                with self.subTest(name=name, method=method):
                                    status, body, headers = request(base + name + '?preview=1', [authority], method)
                                    self.assertEqual(status, 200)
                                    self.assertEqual(dict(headers)['Cache-Control'], 'no-store')
                                    if method == 'HEAD':
                                        self.assertEqual(body, b'')
                        # Directory symlinks must not bypass the leaf-only file check.
                        assets = Path(directory) / 'assets'
                        saved = Path(directory) / 'saved-assets'
                        assets.rename(saved)
                        assets.symlink_to(saved, target_is_directory=True)
                        try:
                            status, body, _ = request(base + 'assets/orbit.svg', [authority])
                            self.assertEqual(status, 404)
                        finally:
                            assets.unlink()
                            saved.rename(assets)
                    finally:
                        http.shutdown()
                        worker.join(timeout=3)
                        self.assertFalse(worker.is_alive())


if __name__ == '__main__':
    unittest.main()
