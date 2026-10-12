# SPDX-License-Identifier: MIT
"""Pinned browser installation boundaries; synthetic archives, no network in units."""
import hashlib
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import install_browser


class BrowserToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='twitch-screen-browser-tools-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'browser.zip'
        self.output = self.root / 'installed'
        self.data = install_browser.baseline()

    def archive_with(self, extra=()):
        with zipfile.ZipFile(self.archive, 'w') as bundle:
            bundle.writestr(self.data['executable'], b'synthetic test executable')
            for name, content in extra:
                bundle.writestr(name, content)
        self.data['archiveBytes'] = self.archive.stat().st_size
        self.data['sha256'] = hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_exact_archive_extracts_and_never_overwrites(self):
        self.archive_with()
        executable = install_browser.extract(self.archive, self.output, self.data)
        self.assertEqual(executable.read_bytes(), b'synthetic test executable')
        self.assertEqual(stat.S_IMODE(executable.stat().st_mode), 0o700)
        with self.assertRaisesRegex(ValueError, 'empty'):
            install_browser.extract(self.archive, self.output, self.data)
        self.assertEqual(executable.read_bytes(), b'synthetic test executable')

    def test_hash_and_size_rejected_before_destination_creation(self):
        self.archive_with()
        for key, value in (('sha256', '0' * 64), ('archiveBytes', 1)):
            changed = {**self.data, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                install_browser.extract(self.archive, self.output, changed)
            self.assertFalse(self.output.exists())

    def test_traversal_alias_symlink_and_special_members_rejected(self):
        link = zipfile.ZipInfo('chrome-headless-shell-linux64/link')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        fifo = zipfile.ZipInfo('chrome-headless-shell-linux64/fifo')
        fifo.create_system = 3
        fifo.external_attr = (stat.S_IFIFO | 0o600) << 16
        for name in ('../escape', '/escape', 'other/file', 'chrome-headless-shell-linux64/../escape',
                     'chrome-headless-shell-linux64//alias', 'chrome-headless-shell-linux64/./alias',
                     'chrome-headless-shell-linux64\\escape', link, fifo):
            self.archive_with([(name, b'bad')])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'unsafe'):
                install_browser.extract(self.archive, self.output, self.data)
            self.assertFalse(self.output.exists())

    def test_member_type_and_parent_conflicts_rejected_before_extraction(self):
        directory_as_file = zipfile.ZipInfo('chrome-headless-shell-linux64/conflict')
        directory_as_file.external_attr = (stat.S_IFDIR | 0o700) << 16
        file_as_directory = zipfile.ZipInfo('chrome-headless-shell-linux64/conflict/')
        file_as_directory.external_attr = (stat.S_IFREG | 0o600) << 16
        cases = (
            [(directory_as_file, b'bad')],
            [(file_as_directory, b'')],
            [('chrome-headless-shell-linux64', b'file')],
            [('chrome-headless-shell-linux64/parent', b'file'),
             ('chrome-headless-shell-linux64/parent/child', b'child')],
            [('chrome-headless-shell-linux64/parent/child', b'child'),
             ('chrome-headless-shell-linux64/parent', b'file')],
            [(self.data['executable'] + '/child', b'child')],
        )
        for index, extra in enumerate(cases):
            self.archive_with(extra)
            output = self.root / f'conflict-{index}'
            with self.subTest(case=index):
                with self.assertRaisesRegex(ValueError, 'unsafe'):
                    install_browser.extract(self.archive, output, self.data)
                self.assertFalse(output.exists())

    def test_explicit_directories_and_implicit_parents_extract(self):
        directory = zipfile.ZipInfo('chrome-headless-shell-linux64/extra/')
        directory.external_attr = (stat.S_IFDIR | 0o700) << 16
        self.archive_with([(directory, b''),
                           ('chrome-headless-shell-linux64/extra/child', b'child'),
                           ('chrome-headless-shell-linux64/implicit/child', b'implicit')])
        install_browser.extract(self.archive, self.output, self.data)
        self.assertEqual((self.output / 'chrome-headless-shell-linux64/extra/child').read_bytes(),
                         b'child')
        self.assertEqual((self.output / 'chrome-headless-shell-linux64/implicit/child').read_bytes(),
                         b'implicit')

    def test_symlink_destination_and_archive_expansion_rejected(self):
        self.archive_with()
        self.output.symlink_to(self.root / 'elsewhere')
        with self.assertRaisesRegex(ValueError, 'symlink'):
            install_browser.extract(self.archive, self.output, self.data)
        self.output.unlink()
        with patch.object(install_browser, 'MAX_EXPANDED_BYTES', 1):
            with self.assertRaisesRegex(ValueError, 'expansion'):
                install_browser.extract(self.archive, self.output, self.data)
        self.assertFalse(self.output.exists())

    def test_download_redirect_and_oversize_fail_closed(self):
        self.archive_with()
        for redirect, payload in ((True, b''), (False, b'x' * (self.data['archiveBytes'] + 1))):
            response = io.BytesIO(payload)
            response.geturl = lambda: 'https://example.invalid/browser.zip' if redirect else self.data['url']
            with patch.object(install_browser, 'baseline', return_value=self.data), \
                    patch.object(install_browser.platform, 'system', return_value='Linux'), \
                    patch.object(install_browser.platform, 'machine', return_value='x86_64'), \
                    patch.object(install_browser.urllib.request, 'urlopen', return_value=response):
                with self.subTest(redirect=redirect), self.assertRaises(ValueError):
                    install_browser.install(self.output)
            self.assertFalse(self.output.exists())

    def test_baseline_strict_types_and_exact_url(self):
        original = install_browser.baseline()
        for key, value in (('version', None), ('schemaVersion', True), ('archiveBytes', True),
                           ('url', 'https://example.invalid/browser.zip'), ('channel', 'Beta')):
            with patch.object(Path, 'read_text', return_value=json.dumps({**original, key: value})):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    install_browser.baseline()


if __name__ == '__main__':
    unittest.main()
