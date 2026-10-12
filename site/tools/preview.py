#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Loopback-only static preview. Serves only validated output at the selected base."""
import argparse
from contextlib import contextmanager, ExitStack
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
from urllib.parse import unquote, urlsplit

from artifact_archive import archive_directory
from build import ASSETS, MAX_RAW_BYTES, base_path, build
from verify_artifact import read_member, verify

PUBLIC_FILES = frozenset(ASSETS) | {'index.html', '.nojekyll', 'build-manifest.json'}

CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
       "connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; "
       "form-action 'none'; frame-ancestors 'none'")


class PreviewHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, directory, base, **kwargs):
        self.base = base
        super().__init__(*args, directory=str(directory), **kwargs)

    def _route(self, head=False):
        # A loopback bind alone does not reject browser DNS-rebinding authorities.
        hosts = self.headers.get_all('Host', [])
        authority = f'127.0.0.1:{self.server.server_port}'
        if hosts != [authority]:
            self.send_error(421, 'Preview requires its exact loopback authority')
            return
        if not self.path.startswith('/'):
            self.send_error(404)
            return
        target_url = urlsplit(self.path)
        raw = target_url.path
        path = unquote(raw)
        if (target_url.scheme or target_url.netloc or target_url.fragment
                or not raw.startswith('/') or raw != path
                or ".." in path.split("/") or "\\" in path):
            self.send_error(404)
            return
        if path == self.base.rstrip("/") and self.base != "/":
            self.send_response(308)
            self.send_header("Location", self.base)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if not path.startswith(self.base):
            self.send_error(404)
            return
        relative = path[len(self.base):] or "index.html"
        target = Path(self.directory) / relative
        if (relative not in PUBLIC_FILES or not target.is_file()
                or any(part.is_symlink() for part in (target, *target.parents))):
            self.send_error(404)
            return
        self.path = "/" + relative
        if head:
            super().do_HEAD()
        else:
            super().do_GET()

    def do_GET(self):
        self._route()

    def do_HEAD(self):
        self._route(head=True)

    def end_headers(self):
        self.send_header("Content-Security-Policy", CSP)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def validate_port(port):
    if type(port) is not int or not 0 <= port <= 65535:
        raise ValueError('port must be an integer from 0 to 65535')
    return port


def server(directory, base="/twitch-screen/", port=0):
    validate_port(port)
    return ThreadingHTTPServer(("127.0.0.1", port), partial(
        PreviewHandler, directory=directory, base=base_path(base)))


@contextmanager
def preview_directory(artifact=None, base=None, expected_manifest_sha256=None):
    """Serve an owned snapshot, never a mutable external build directory."""
    if artifact is None and expected_manifest_sha256 is not None:
        raise ValueError('expected manifest identity requires --artifact or --archive')
    with tempfile.TemporaryDirectory(prefix="twitch-screen-preview-") as directory:
        if artifact is None:
            selected_base = base_path('/twitch-screen/' if base is None else base)
            build(directory, selected_base)
        else:
            artifact = Path(artifact)
            result = (verify(artifact) if expected_manifest_sha256 is None
                      else verify(artifact, expected_manifest_sha256))
            selected_base = result['basePath']
            if base is not None and base_path(base) != selected_base:
                raise ValueError('requested base differs from artifact manifest')
            remaining = MAX_RAW_BYTES
            for name in sorted(PUBLIC_FILES):
                data = read_member(artifact, name, remaining)
                remaining -= len(data)
                target = Path(directory) / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            # A changed input must not bypass admission between validation and copying.
            if expected_manifest_sha256 is None:
                verify(directory)
            else:
                verify(directory, expected_manifest_sha256)
        yield Path(directory), selected_base


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-path")
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument("--artifact", type=Path,
                        help="preview a verified existing build without rebuilding")
    inputs.add_argument("--archive", type=Path,
                        help="preview a bounded downloaded static artifact ZIP")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--expected-manifest-sha256", help="require independently reviewed artifact identity")
    args = parser.parse_args()
    try:
        validate_port(args.port)
        with ExitStack() as inputs:
            if not (args.artifact or args.archive) and args.expected_manifest_sha256 is not None:
                raise ValueError('expected manifest identity requires --artifact or --archive')
            artifact = (inputs.enter_context(archive_directory(args.archive))
                        if args.archive else args.artifact)
            directory, base = inputs.enter_context(preview_directory(
                artifact, args.base_path, args.expected_manifest_sha256))
            with server(directory, base, args.port) as http:
                print(f"Preview: http://127.0.0.1:{http.server_port}{base}", flush=True)
                print("Local illustration only. Ctrl+C stops preview.", flush=True)
                try:
                    http.serve_forever()
                except KeyboardInterrupt:
                    pass
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"preview failed: {error}\n")


if __name__ == "__main__":
    main()
