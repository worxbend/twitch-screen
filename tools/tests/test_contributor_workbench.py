"""Contributor entry points must resolve and describe the current build boundary."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ContributorWorkbenchTests(unittest.TestCase):
    def test_agent_entry_point_and_local_links_resolve(self):
        instructions = ROOT / 'AGENTS.md'
        self.assertTrue(instructions.is_file())
        self.assertEqual((ROOT / 'CLAUDE.md').read_text().strip(), '@AGENTS.md')
        for name in ('CONTRIBUTING.md', 'docs/guides/development.md'):
            document = ROOT / name
            for target in re.findall(r'\]\(([^\s)]+)\)', document.read_text()):
                if '://' in target or target.startswith('#'):
                    continue
                with self.subTest(document=name, target=target):
                    resolved = document.parent / target.split('#', 1)[0]
                    self.assertTrue(resolved.is_file() or resolved.is_dir())

    def test_workbench_has_fresh_tests_and_no_obsolete_auth_commands(self):
        guide = (ROOT / 'docs/guides/development.md').read_text()
        self.assertIn('./mill --no-server -j 1 test.testForked', guide)
        self.assertIn('pio test -e native-lvgl-base', guide)
        self.assertIn('pio test -e native-generic', guide)
        self.assertIn('python3 tools/compose.py --env-file .env.example up --build -d', guide)
        self.assertNotIn('hash_management_password.py', guide)
        self.assertNotIn('with management credentials configured', guide)
        self.assertNotIn('Complete DESK delivery, admin and runtime-channel behavior remain pending', guide)

    def test_instructions_separate_release_from_device_and_site_deployment(self):
        instructions = (ROOT / 'AGENTS.md').read_text()
        for boundary in ('requires separate authorization', 'Never move a published tag',
                         'redistribution-rights gates remain unresolved',
                         'Tagging does not authorize Pages deployment',
                         'Do not overwrite an existing credentials header'):
            with self.subTest(boundary=boundary):
                self.assertIn(boundary, instructions)


if __name__ == '__main__':
    unittest.main()
