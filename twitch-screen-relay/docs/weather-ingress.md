# Weather ingress implementation boundary

`GET /api/v1/weather` and `PUT /api/v1/weather` are mounted in the production relay. The hub actor owns one RAM-only latest value and captures weather readiness with display intent and CPU readiness in one publication transaction. No weather provider, geolocation, data history, notification event or dashboard selection is created by a write.

Required fields are `sourceId`, `location`, `temperature` and `condition`. Unit defaults to `celsius`; `fahrenheit` is explicit. Conditions are `unknown`, `clear`, `partly_cloudy`, `cloudy`, `fog`, `drizzle`, `rain`, `snow` and `thunderstorm`. Temperature bounds are −100…100 C or −148…212 F, checked before half-away-from-zero rounding to hundredths. Optional `humidityPercent` is an integer from 0 through 100; omitted means unavailable and zero means measured zero.

`sourceId` contains 1–31 ASCII letters, digits, dots, underscores or hyphens. Location is nonblank valid Unicode with at most 128 scalar values. Requests reject duplicate/unknown keys, explicit null, fractional integer fields, malformed timestamps and invalid units/conditions before admission. Integral decimal and exponent JSON number forms are accepted for integer fields.

Freshness defaults to 600000 ms and accepts 1000…1800000 ms. The optional calibrated `deadline` uses the existing relay epoch and monotonic domain. Its effective deadline is the earlier of the supplied value and receipt plus freshness. A mismatched valid epoch returns 409; expired data returns 410. Both leave the slot unchanged. Successful writes carry same-exchange calibration headers. Producer `observedAt` is informative metadata only; omission records relay receipt time. It never renews freshness.

Admission is independently limited to 10 writes/s with burst 20. Excess returns 429 without mutation. Each accepted replacement advances the weather revision; missing starts at revision 1 and revision exhaustion returns 503 without wrapping. GET retains diagnostic data when stale, with zero remaining freshness. Restart restores desired display intent but not this RAM-only source.

Example on the default loopback deployment:

```sh
curl --fail --silent --show-error -X PUT \
  -H 'Content-Type: application/json' \
  --data '{"sourceId":"weather-example","location":"Desk","temperature":21.25,"condition":"clear"}' \
  http://localhost:8080/api/v1/weather
```

This ordinary example uses receipt-based freshness. A producer requiring age preservation across upload delay must calibrate through `/api/v1/clock` and supply its original absolute deadline. Do not blindly retry expired observations with a renewed lifetime.

Verification: `./mill --no-server -j 1 test.testOnly '*WeatherIngressSuite'` exercises validation, quota, revision exhaustion, deadline and coherent display readback. After assembly, `tools/check_runtime_pairing.py --evidence-dir PATH --verify-ingress` also checks actual weather HTTP admission/readback/expiry and fresh-process missing state alongside actual companion CPU/card pairing.

Pending: negotiated DESK delivery, at-most-five-updates/s wire coalescing, shared discontinuity invalidation and actual LCD weather rendering. HTTP acceptance and display readiness are not device application or physical pixel evidence. Capability advertisement remains disabled.
