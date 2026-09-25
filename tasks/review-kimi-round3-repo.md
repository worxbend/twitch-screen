# Kimi review remediation, round 3: repository tooling

Scope: follow-up gaps on `REVIEW_SUMMARY.kimi.md` repo-area findings. Evidence
only. The integrating agent owns `tasks/review-kimi-summary.md` and `tasks/todo.md`.

## K-133 (Low): check_protocol_vectors.py brittle anchors fail with bare tracebacks

**Disposition:** fixed.

**Gaps closed.** `bytes.fromhex` errors did not say which vector failed. Nothing
tested the failure paths of `check_protocol_vectors.py` itself.

**Change: `tools/check_protocol_vectors.py`**
- `decode_hex(number, block, origin)` re-raises a malformed block as
  `ValueError("V<n>: malformed hex block in <origin>: …")`. The origin is the
  spec file name, or `relay Tsb3Vectors.scala` on the relay side.
- Relay parsing moved into `relay_vectors(source=RELAY_VECTORS)`, which reads
  the file as UTF-8.
- `main(argv=None)` takes one optional spec path and prints a usage message if
  given more than one argument. `spec_vectors(spec)` now runs before
  `gen_vectors.py`, so a bad spec fails with this script's named message. The
  drift messages and the `__main__` handler (ValueError, OSError and
  CalledProcessError become `Protocol vector check failed: …` with no traceback)
  are unchanged. CI (`ci.yml:47`) still runs the script with no arguments.

**Tests: `tools/tests/test_protocol_vectors.py`**
- Red before the fix, green after:
  - `test_check_spec_vectors_names_vector_with_malformed_hex`
  - `test_check_relay_vectors_names_vector_with_malformed_hex`, with two
    subtests: a multi-line V5 and a single-line V3.
  - `test_check_script_fails_cleanly_on_bad_spec`, with two subtests: a renamed
    anchor and a corrupt V5. Each checks for a non-zero exit, "Protocol vector
    check failed" and the finding keyword, and that stderr has no `Traceback`.
- Regression locks, green both before and after:
  - `test_check_spec_vectors_rejects_renamed_start_anchor`
  - `test_check_spec_vectors_rejects_swapped_anchors`
  - `test_check_script_passes_on_real_tree`

**Red run (tests only):** `python3 -m unittest discover -s tools/tests` gave
`Ran 17 tests … FAILED (failures=3, errors=2)`. The failures were the three
red tests above: the spec test once and each of the other two in both
subtests.

**Green run, from the worktree root:**
- `python3 -m unittest discover -s tools/tests -v` gave `Ran 17 tests … OK`:
  the 11 existing tests plus 6 new ones.
- `python3 tools/check_protocol_vectors.py` printed `All 20 TSB/3 vectors match
  the specification, relay and firmware.` and exited 0.
- `python3 -m py_compile tools/check_protocol_vectors.py tools/tests/test_protocol_vectors.py`
  passed.
- Manual failure samples, each exiting 1 with no traceback:
  - `Protocol vector check failed: V5: malformed hex block in bad.md: non-hexadecimal number found in fromhex() arg at position 0`
  - `Protocol vector check failed: PROTOCOL.md must contain one ordered pair of tsb3-golden-vectors anchors`
  - Two arguments print `usage: check_protocol_vectors.py [PROTOCOL.md]`.
- `git diff --stat` touches only the two tools files and this record.

## K-115 (Low): static const in header instead of constexpr

**Disposition:** fixed.

**Gap closed.** The production headers already used `constexpr`, but the
generated test fixture `twitch-screen-firmware/test/test_proto_codec/vectors.h`
still had 59 `static const` declarations. They came from six emitter strings in
`gen_vectors.py`. Four test TUs include the header: `test_proto_codec`,
`test_link_wire`, `test_link_session` and `test_parser_fuzz`.

**Change**
- `twitch-screen-firmware/test/test_proto_codec/gen_vectors.py`: the emitters
  at lines 206 (frame `uint8_t` arrays), 215 (`char` string arrays), 259
  (`<LABEL>_VECTORS[]` tables), 270 (`<LABEL>_COUNT`), 278 (`ALL_VECTORS[]`) and
  283 (`ALL_COUNT`) now write `static constexpr`. The struct definitions and
  member types (`const char *`, `const uint8_t *`) did not change.
- `vectors.h` was regenerated with
  `python3 test/test_proto_codec/gen_vectors.py docs/PROTOCOL.md test/test_proto_codec/vectors.h`.
  It has 59 lines added and 59 removed. After the old header is rewritten with
  `sed 's/^static const /static constexpr /'`, it is byte-identical to the new
  one. `grep -c '^static const '` gives 0 and `grep -c '^static constexpr '`
  gives 59.

**Test: `tools/tests/test_protocol_vectors.py`**
- `test_generated_header_uses_constexpr_declarations` runs the generator on the
  real spec into a temporary directory and asserts the following:
  - the generator exits 0;
  - the output has no line matching `^static const\b`;
  - the output contains `static constexpr`;
  - the committed `vectors.h` has no line matching `^static const\b`.
- Red before the fix: `python3 -m unittest discover -s tools/tests -v` gave
  `Ran 18 tests … FAILED (failures=1)`, with `match='static const'`.

**Green run, from the worktree root:**
- `python3 -m unittest discover -s tools/tests -v` gave `Ran 18 tests … OK`.
- `python3 tools/check_protocol_vectors.py` printed `All 20 TSB/3 vectors match
  the specification, relay and firmware.` and exited 0, so the regenerated
  header matches the generator byte for byte.
- `pio test -e native -e native-sanitized` in `twitch-screen-firmware` gave
  `10 test cases: 10 succeeded`. The four TUs that include `vectors.h` built
  under `-Wall -Wextra -Werror` in both envs, with ASan/UBSan in
  `native-sanitized`. The PlatformIO venv's `pio` shim fails on this host,
  because the system Python is now 3.14 and the venv was built for 3.13. The
  same PlatformIO 6.2.0 was therefore run with uv's CPython 3.13.15 and
  `PYTHONPATH=~/.platformio/penv/lib/python3.13/site-packages`. This affects
  only the local launcher. The test commands are unchanged.
- `git diff --stat` touches only `gen_vectors.py`, `vectors.h`,
  `tools/tests/test_protocol_vectors.py` and this record.
