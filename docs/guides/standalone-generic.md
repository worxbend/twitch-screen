# Standalone Generic screensaver

Generic is a device-local screensaver. It does not need the relay to fetch its
photos or Kyiv weather. The relay admin's **Generic** Display intent selects the
same screensaver when the relay is connected; it is also the controller's Generic
fallback when the relay is absent. Selecting Twitch, CPU or relay Weather retains
that dashboard's existing controller/freshness policy. Notifications still cover
the base and return to the current selection.

## Connectivity and presentation

Wi-Fi association, public-internet reachability and the relay connection are
separate states:

- **No Wi-Fi:** red/yellow drifting, breathing gas animation; no invented readings.
- **Checking internet:** Wi-Fi has associated, but public connectivity has not
  yet been established. The offline animation remains visible.
- **No internet:** public requests have failed and the previous reachability
  evidence is no longer current. The animation remains visible even if Wi-Fi is
  still associated.
- **Internet available, relay absent:** photos and local Kyiv weather work without
  the relay. A relay connection failure does not become a `No Wi-Fi` message.
- **Internet available, Generic selected through the relay:** the same photo and
  weather screensaver is displayed.

The slideshow uses five-second visible photo intervals. Every photo has overlaid
Kyiv location, outdoor temperature in Celsius and weather condition; there is no
separate weather slide. The next random photo is prefetched into the spare slot.
If the download is late or fails, the current photo remains visible until another
photo is ready. Missing or stale weather is marked unavailable rather than shown
as a current measurement. A failed photo
provider does not establish a whole-internet outage when weather still proves
public reachability. Cached photos alone never renew connectivity evidence.

Other dashboards and notification overlays suspend Generic presentation and
photo requests. Network requests and progressive JPEG decoding run in a separate
bounded worker, not the LVGL/application loop. Wi-Fi reconnect ownership remains
with the existing link transport.

## Public services and limits

- Photos: [Lorem Picsum](https://picsum.photos/). Each new photo starts with a live
  `https://picsum.photos/96/96.jpg?random=<new nonce>` request, allowing Picsum to
  select a random image. The nonce prevents reusing a cached random redirect.
  The firmware follows the provider-issued signed HTTPS URL to
  `https://fastly.picsum.photos/` and downloads the JPEG. There is no fixed catalog
  or bundled-photo fallback. The provider may randomly select the same image
  again; random does not guarantee uniqueness.
  Redirects cannot downgrade to HTTP or escape the explicit photo-host allowlist.
- Local weather: [Open-Meteo](https://open-meteo.com/) current model estimates for
  Kyiv, Ukraine (`50.45466`, `30.5238`), Celsius. Weather refreshes every five
  minutes while Generic is active, with bounded retries on failure. This local
  data is separate from the relay's built-in Weather snapshot.
- Clock: public SNTP establishes a usable clock before TLS certificate validity
  checks. An unavailable clock can prevent public-service access.

No weather/photo API key or additional relay setting is needed. Existing device
Wi-Fi configuration is unchanged. HTTPS certificate and hostname verification are
required; the firmware does not use an insecure TLS fallback.

The WROOM target has no PSRAM. It requests **96 × 96** JPEG photos and scales them
to the 240 × 240 panel; typography stays native resolution. This is deliberately
lower-resolution photography, not a full-resolution 240-pixel download. Encoded
bodies, headers, redirects, JPEG dimensions and decoder allocations are bounded.
The JPEG converter packs RGB565 one row at a time instead of allocating a full
RGB24 output. The synchronous display uses an eight-row partial draw tile to
leave contiguous byte-addressable DRAM available for TLS and JPEG decoding.
Only immutable active pixels are exposed to LVGL, and image caches are invalidated
before a buffer is returned to the worker.

Open-Meteo's free endpoint is for non-commercial use and requires attribution;
see [the weather guide](weather.md) and
[provider terms](https://open-meteo.com/en/terms). Picsum supplies third-party
photography. Public availability is not a new project-owned redistribution grant;
respect the provider and photographer terms. Runtime photos are not bundled into
firmware or added as project-owned release artwork. The vendored progressive JPEG
decoder has its own retained license and documented upstream modifications.

## Verification boundaries

Parser/state tests, real-JPEG decoding under sanitizers and native LVGL framebuffer
captures exercise different layers. A firmware build does not prove target heap
headroom, public-network success, relay-independent operation or physical LCD
pixels. Those require separate connected-board observations. Generated/native
captures are not photographs of the physical display, and simulated outages are
not evidence of an actual Wi-Fi or WAN outage.
