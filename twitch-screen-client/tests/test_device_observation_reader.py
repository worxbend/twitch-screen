"""Owned async native witness admission uses real disposable child pipes."""
import asyncio
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_runtime_device as device


class ObservationReaderTests(unittest.IsolatedAsyncioTestCase):
    async def read_child(self, script):
        child = await asyncio.create_subprocess_exec(sys.executable, '-c', script,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=8192)
        self.addAsyncCleanup(self.close_child, child)
        records, changed = [], asyncio.Event()
        await device.read_observations(child.stdout, records, changed)
        await asyncio.wait_for(child.wait(), 2)
        self.assertTrue(changed.is_set())
        return records

    async def close_child(self, child):
        if child.returncode is None:
            child.kill()
        await child.communicate()

    async def test_fragmented_utf8_and_final_eof_record(self):
        rows = await self.read_child("import os; os.write(1,b'{\"event\":\"'); "
            "os.write(1,bytes([0xc3])); os.write(1,bytes([0xa9])+b'\"}\\n{\"event\":\"end\"}')")
        self.assertEqual(rows, [{'event': 'é'}, {'event': 'end'}])

    async def test_oversized_unterminated_and_terminated_lines_fail_closed(self):
        for suffix in ('', '\n'):
            with self.subTest(suffix=suffix), self.assertRaises((ValueError, AssertionError)):
                await self.read_child(f'import os; os.write(1, b"x" * 8193 + {suffix.encode()!r})')

    async def test_malformed_utf8_json_or_nonobject_never_enters_records(self):
        for wire in (b'\xff\n', b'{broken\n', b'[]\n', b'{"event":"a","event":"b"}\n'):
            with self.subTest(wire=wire), self.assertRaises((ValueError, UnicodeError)):
                await self.read_child(f'import os; os.write(1, {wire!r})')

    async def test_record_limit_never_silently_drops_witnesses(self):
        child = await asyncio.create_subprocess_exec(sys.executable, '-c',
            'import os; os.write(1,b"{\\\"event\\\":\\\"extra\\\"}\\n")', stdout=asyncio.subprocess.PIPE)
        self.addAsyncCleanup(self.close_child, child)
        records = [{'event': 'existing'}] * 10000
        changed = asyncio.Event()
        with self.assertRaises(ValueError):
            await device.read_observations(child.stdout, records, changed)
        self.assertEqual(len(records), 10000)
        self.assertTrue(changed.is_set(), 'reader failure must wake evidence waiters')

    async def test_cancellation_wakes_waiters_and_does_not_retain_partial_record(self):
        stream = asyncio.StreamReader(limit=8192)
        stream.feed_data(b'{"event":')
        changed, records = asyncio.Event(), []
        task = asyncio.create_task(device.read_observations(stream, records, changed))
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(records, [])
        self.assertTrue(changed.is_set())
