# Firmware runtime measurements

Production `main.cpp` records bounded timing observations without allocating or
sorting in the loop. `runtime_metrics.h` uses fixed cumulative histograms for
loop-to-loop intervals and the UI section. Native and sanitizer tests cover
percentile conservatism, exact target boundaries, unsigned microsecond wrap,
missing observations and independent resource minima.

Every 60 seconds, best-effort serial logging emits three independently bounded
lines sharing `uptimeMs`: `[metrics-loop]`, `[metrics-ui]` and
`[metrics-resources]`. UART congestion may drop a line; absence is not a zero or
a pass. The existing reset reason, watchdog initialization and stack diagnostic
remain available. No notification text, credentials or telemetry labels are
included in these measurement lines.

## Definitions

- Loop interval: start of one `loop()` to start of the next. Includes link work,
  UI work, logging, the existing 5 ms yield and scheduler delays.
- UI section: after `linkLoop()`, through deadline/view reconciliation, LVGL
  pumping and possible card start. This includes LCD flush work performed by the
  pump. It does not measure networking inside `linkLoop()`.
- `samples`: cumulative samples since boot. The first loop has no preceding
  interval; UI sample count therefore exceeds loop sample count by one.
- `p95UpperUs`: conservative upper bound on the nearest-rank 95th percentile,
  not an interpolated exact percentile. Fixed buckets include the 5000 us UI
  target and 50000 us loop target. A bound above a target may be inconclusive;
  it is not automatically evidence that the exact percentile exceeds the target.
- `maxUs`: largest observed duration. The overflow bucket uses the observed
  maximum rather than understating durations above 200000 us.
- `saturated=1`, no samples, or `p95UpperUs=4294967295`: percentile unavailable.
  Counters stop before overflow. Normal `micros()` wrap is handled by unsigned
  subtraction; intervals spanning multiple wraps require other evidence.
- `sampledFreeHeapMinBytes` and `sampledLargestBlockMinBytes`: minima among the
  once-per-minute `MALLOC_CAP_8BIT` samples, not continuous allocator minima.
  Transient fragmentation or allocation lows between samples are not proven.
- `stackMinBytes`: minimum among sampled loop-task high-water marks. ESP-IDF's
  task high-water unit here is bytes, not words.

The Linux application peer emits one `runtime_metrics` JSON record using the
same production histogram and real application/LVGL execution. These are Linux
ASan/memory-display observations, not ESP32 performance or LCD evidence. The
normal and short-write executable regressions require real nonempty metrics and
correct sample-count semantics, without imposing host-dependent latency targets.

## Exclusive integrator acceptance

Only the exclusive integrator accesses the board. After target configuration,
complete capable pairing and resource ownership are verified, capture serial
output during the authorized 60-minute mixed-mode/card/reconnect soak. Preserve
raw lines, resets, watchdog status, firmware identity and physical LCD evidence.
Missing lines, saturation, a reboot or an incomplete interval need an explicit
explanation; do not infer uninterrupted soak from the last uptime alone.

PLAN targets remain p95 UI <=5000 us, p95 loop <=50000 us, no UI-attributable
loop interval >200000 us, minimum free heap >=40960 bytes, largest block >=16384
bytes, stack headroom >=2048 bytes and zero watchdog resets. These are acceptance
targets, not current measurements. A large loop maximum needs attribution before
blaming the UI. Sampled resource minima alone cannot establish continuous heap
or fragmentation targets; collect allocator tracing/additional scoped samples
when needed. Actual RGB565 stepping, seams, smoothness and text fit still need
LCD observation. This instrumentation neither enables DESK nor changes
`productAccepted`.
