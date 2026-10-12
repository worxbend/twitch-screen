import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location('admin_https', TOOLS / 'check_admin_https.py')
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = TOOLS.parents[1]
SCHEMA = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
EXAMPLES = json.loads((ROOT / 'protocol/desk-v1-http-examples.json').read_text())
SNAPSHOT = next(row['value'] for row in EXAMPLES['examples'] if row['schema'] == 'AdminSnapshot')


class AdminHttpsReaderTests(unittest.TestCase):
    def frame(self, value=None, event='snapshot', identity=None):
        value = copy.deepcopy(SNAPSHOT if value is None else value)
        identity = identity or f"{value['relayEpoch']}:{value['revision']}"
        return io.BytesIO(f'event: {event}\nid: {identity}\ndata: {json.dumps(value)}\n\n'.encode())

    def test_complete_snapshot_and_reset_validate_actual_contract(self):
        for kind in ('snapshot', 'reset'):
            with self.subTest(kind=kind):
                result = MODULE.read_event(self.frame(event=kind), SCHEMA)
                self.assertEqual(result['snapshot'], SNAPSHOT)
                self.assertEqual(result['event'], kind)

    def test_comments_do_not_claim_snapshot_identity(self):
        self.assertEqual(MODULE.read_event(io.BytesIO(b': heartbeat\n\n'), SCHEMA), {'comment': ': heartbeat'})

    def test_mismatched_identity_rejects(self):
        with self.assertRaisesRegex(ValueError, 'identity'):
            MODULE.read_event(self.frame(identity='0123456789abcdef:99'), SCHEMA)

    def test_schema_invalid_complete_snapshot_rejects(self):
        value = copy.deepcopy(SNAPSHOT)
        value['sources']['cpu']['data'] = {'cpuPercent': 'invented'}
        with self.assertRaises((ValueError, AssertionError)):
            MODULE.read_event(self.frame(value), SCHEMA)

    def test_unknown_event_rejects(self):
        with self.assertRaisesRegex(ValueError, 'unknown admin event'):
            MODULE.read_event(self.frame(event='delta'), SCHEMA)

    def test_eof_and_oversized_line_fail_closed(self):
        for raw in (b'', b'x' * 524289):
            with self.subTest(size=len(raw)), self.assertRaisesRegex(ValueError, 'ended or exceeded'):
                MODULE.read_event(io.BytesIO(raw), SCHEMA)


if __name__ == '__main__':
    unittest.main()
