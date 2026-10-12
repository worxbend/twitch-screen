# SPDX-License-Identifier: MIT
"""Port admission precedes build, artifact reads and socket allocation."""
from contextlib import redirect_stderr
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import preview


class PreviewPortTests(unittest.TestCase):
    def test_library_rejects_invalid_ports_before_socket_creation(self):
        for port in (-1, 65536, 999999999999, True, False, 1.5, '8000', None):
            with self.subTest(port=port), patch.object(preview, 'ThreadingHTTPServer') as listener:
                with self.assertRaisesRegex(ValueError, 'port must be an integer from 0 to 65535'):
                    preview.server('/not-read', port=port)
                listener.assert_not_called()
        self.assertEqual(preview.validate_port(0), 0)
        self.assertEqual(preview.validate_port(65535), 65535)

    def test_cli_rejects_port_before_any_input_admission(self):
        for arguments in ([], ['--artifact', '/not-read'], ['--archive', '/not-read.zip']):
            with self.subTest(arguments=arguments), patch.object(sys, 'argv', [
                    'preview.py', *arguments, '--port', '65536']), \
                    patch.object(preview, 'preview_directory') as directory, \
                    patch.object(preview, 'archive_directory') as archive, \
                    redirect_stderr(io.StringIO()) as errors:
                with self.assertRaises(SystemExit) as rejected:
                    preview.main()
                self.assertEqual(rejected.exception.code, 1)
                self.assertIn('port must be an integer from 0 to 65535', errors.getvalue())
                directory.assert_not_called()
                archive.assert_not_called()


if __name__ == '__main__':
    unittest.main()
