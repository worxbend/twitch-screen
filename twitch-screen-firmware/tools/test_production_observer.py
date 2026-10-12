#!/usr/bin/env python3
"""Exercise the test observer's pipe isolation and fail-closed bounds."""
import queue
import subprocess
import sys
import time
import tracemalloc
import unittest
from test_production_views import observation_lines


class ObserverTests(unittest.TestCase):
    def child(self, fixture):
        child = subprocess.Popen([sys.executable, __file__, '--fixture', fixture],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert child.stdout is not None and child.stderr is not None
        return child

    def test_slow_http_equivalent_does_not_block_the_child_pipe(self):
        child = self.child('bounded')
        lines = observation_lines(child)
        try:
            first = next(lines)
            # This is a bounded test-only delay, not application polling.
            time.sleep(.2)
            child.wait(timeout=2)  # The child wrote far beyond the kernel pipe.
            self.assertEqual(len([first] + list(lines)), 800)
            self.assertEqual(child.returncode, 0)
        finally:
            lines.close()
            if child.poll() is None: child.kill(); child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_oversized_observation_fails_without_dropping_it(self):
        child = self.child('oversized')
        try:
            with self.assertRaisesRegex(AssertionError, 'exceeded fixture bound'):
                list(observation_lines(child))
        finally:
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_unterminated_multimegabyte_line_has_bounded_residence(self):
        child = self.child('huge-eof')
        tracemalloc.start()
        started = time.monotonic()
        try:
            with self.assertRaisesRegex(AssertionError, 'exceeded fixture bound'):
                list(observation_lines(child))
            _, peak = tracemalloc.get_traced_memory()
            self.assertLess(peak, 1024 * 1024, 'Reader allocated the oversized record')
            self.assertLess(time.monotonic() - started, 2)
        finally:
            tracemalloc.stop()
            if child.poll() is None: child.kill()
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_unterminated_producer_is_cancelled_before_eof(self):
        child = self.child('huge-open')
        started = time.monotonic()
        try:
            with self.assertRaisesRegex(AssertionError, 'exceeded fixture bound'):
                list(observation_lines(child))
            self.assertLess(time.monotonic() - started, 2)
        finally:
            if child.poll() is None: child.kill()
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_fragmented_utf8_and_final_eof_record(self):
        child = self.child('fragmented')
        try:
            self.assertEqual(list(observation_lines(child)), ['café\n', 'final'])
        finally:
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_utf8_byte_bound_is_not_a_character_bound(self):
        child = self.child('unicode-oversized')
        try:
            with self.assertRaisesRegex(AssertionError, 'exceeded fixture bound'):
                list(observation_lines(child))
        finally:
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_exact_byte_bound_with_newline_and_eof(self):
        child = self.child('exact')
        try:
            rows = list(observation_lines(child))
            self.assertEqual([len(row.encode('utf-8')) for row in rows], [8192, 8192])
        finally:
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_invalid_utf8_fails_closed(self):
        child = self.child('invalid-utf8')
        try:
            with self.assertRaises(UnicodeDecodeError):
                list(observation_lines(child))
        finally:
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()

    def test_overflow_is_a_failure_not_a_dropped_transition(self):
        child = self.child('overflow')
        lines = observation_lines(child)
        try:
            next(lines)
            time.sleep(.2)
            with self.assertRaises(queue.Full):
                list(lines)
        finally:
            lines.close()
            if child.poll() is None: child.kill()
            child.wait(timeout=5)
            child.stdout.close(); child.stderr.close()


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--fixture':
        fixture = sys.argv[2]
        if fixture == 'oversized':
            print('x' * 8193, flush=True)
        elif fixture in ('huge-eof', 'huge-open'):
            # Fixed-size producer chunks; never allocate the whole attack line.
            for _ in range(2048):
                sys.stdout.write('x' * 4096); sys.stdout.flush()
            if fixture == 'huge-open': time.sleep(5)
        elif fixture == 'fragmented':
            for chunk in (b'caf', b'\xc3', b'\xa9\n', b'final'):
                sys.stdout.buffer.write(chunk); sys.stdout.buffer.flush()
                time.sleep(.01)
        elif fixture == 'exact':
            sys.stdout.write('x' * 8191 + '\n' + 'x' * 8192); sys.stdout.flush()
        elif fixture == 'invalid-utf8':
            sys.stdout.buffer.write(b'\xff\n'); sys.stdout.buffer.flush()
        elif fixture == 'unicode-oversized':
            print('é' * 4097, flush=True)
        else:
            for _ in range(800 if fixture == 'bounded' else 2000):
                print('x' * 1024, flush=True)
    else:
        unittest.main()
