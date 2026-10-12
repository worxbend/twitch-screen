Upstream: https://github.com/nothings/stb/tree/2c980bb59875b0d32144a71867fbdebb2f77cd20
Source: https://raw.githubusercontent.com/nothings/stb/2c980bb59875b0d32144a71867fbdebb2f77cd20/stb_image.h
stb_image.h pristine upstream SHA256: 594c2fe35d49488b4382dbfaec8f98366defca819d916ac95becf3e75f4200b3
stb_image 2.30. Upstream dual MIT/public-domain license and Sean Barrett
copyright are retained verbatim in stb_image.h and LICENSE; choose either license.
These are local patches, not claims about pristine upstream strictness:

1. load_jpeg_image releases progressive coefficients (raw_coeff/coeff) after jpeg_finish's
   final dequantization/IDCT, before allocating RGB output. All component planes
   remain alive; cleanup sees null coefficients and is safe on every path.
2. decode_jpeg_image fails on invalid markers instead of returning unfinished
   image success (the earlier local marker patch is retained).
3. Strict entropy completeness, for baseline and progressive JPEG alike:
   - jpeg struct adds a sticky entropy_error and expected restart index.
   - grow_buffer_unsafe stops at markers/EOF, including EOF within marker-fill
     bytes, and never manufactures zero entropy bytes. code_bits counts actual
     available entropy only. Existing Huffman fast/slow and combined fast-AC
     availability checks now enforce this real count; invalid slow Huffman codes
     no longer subtract 16 unavailable bits.
   - extend_receive/get_bits/get_bit latch exhaustion; baseline, progressive DC
     and progressive AC block routines return failure for latched bit errors.
     jpeg_reset initializes the latch for each scan/restart interval.
   - all four MCU loops use jpeg_mcu_done rather than returning success early
     when a restart is missing. Restart indices start at RST0 in each scan and
     advance modulo eight. Missing, out-of-order, premature and unexpected
     restarts fail; no restart is required after the final MCU.
   - entropy_end requires an exhausted EOB run, an actual following marker and
     only 0..7 all-one JPEG pad bits. Extra entropy bytes and unfinished EOB runs
     cannot be silently discarded. A marker encountered by lookahead is fine
     while real buffered bits remain; it is not itself an exhaustion error.
   - decode_jpeg_image no longer skips entropy junk or a trailing restart;
     the unused skip_jpeg_junk_at_end helper was removed.
4. With STBI_JPEG_RGB565_OUTPUT (only generic_decode.cpp enables this), the
   JPEG-only req_comp=3 entry returns packed native-endian RGB565, not RGB24.
   Existing color conversion runs into a bounded 289-byte RGB row, then packs
   into an 18433-byte output allocation. This avoids the 27649-byte contiguous
   RGB24 allocation that failed on the real WROOM despite adequate total free
   memory. Caller output is copied only after complete decode succeeds; existing
   fail-closed entropy checks and cleanup remain unchanged. Generic STB users
   without this macro retain the upstream output format. No image-flip API is
   used by this specialized entry.

No DCT, resampling or color conversion algorithm is changed. There are no
fixture-size heuristics or checksum allowlists. Complete progressive scans and
legal pad bits remain valid; this is not a requirement to transmit every optional
progressive refinement scan. Exact RGB565 comparisons against pristine upstream
cover the original real fixture, three parent-provided real downloads and sixteen
deterministic Pillow/libjpeg grayscale/subsampling/restart layouts.

stb_system.h is a local include wrapper with GCC system_header to contain vendor
warnings, not a changed upstream decoder API. Only JPEG is instantiated in
src/generic_decode.cpp: no stdio, SIMD, HDR or linear conversion. Aggregate
allocation budget includes allocator headers and remains 65536 bytes. Images
must be exactly 96x96 with encoded bodies <=8192 bytes. No full 240px RGB buffer.
Some valid layouts exceed the cap and deliberately fail allocation (progressive
color 4:4:4 and 4:2:2). Baseline 4:4:4 now fits with row-packed RGB565 output.
Unbounded vendor comparison verifies entropy validity separately; this does not
bypass the production budget.

Fault/pixel/cleanup tests: test/test_generic_decode_fault/README.txt.
96x96 RGB565 is enlarged by LVGL to 240x240; overlay text stays native. This is a
WROOM/no-PSRAM constraint, not a claim of native-resolution photos.
