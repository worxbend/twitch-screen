# Round dashboard widgets

The 240 × 240 GC9A01 Weather and CPU dashboards use original instrument-style
graphics inspired by colorful circular gauges: a black face, rounded segmented
arcs, high-contrast Montserrat readings and small condition/core pictograms.
Reference artwork is not copied into the firmware. Generic obtains its photos
from public services at runtime rather than bundling reference photographs.

Select **Generic**, **Weather** or **CPU** using the relay admin's Display intent.
Selecting a dashboard changes presentation, not Twitch ingestion. Notifications
still cover the whole base and reveal the currently selected, fresh dashboard
when they finish. The Twitch dashboard and event-card artwork are unchanged.

## Generic

Generic fetches a Picsum photo slideshow and local Kyiv outdoor weather directly
from public APIs, independently of the relay. A new random photo is requested over
HTTPS for each rotation; there is no fixed catalog. Photos rotate every five visible
seconds with Kyiv weather text over every photo, never a separate weather slide.
Without current internet evidence,
a red-yellow breathing gas animation distinguishes **No Wi-Fi**, **Checking
internet** and **No internet**. A disconnected relay does not imply disconnected
Wi-Fi or internet. Other bases and notifications retain precedence.

See [standalone Generic behavior, services and WROOM limits](standalone-generic.md).

## Weather

The location, exact signed temperature and explicit Celsius/Fahrenheit unit are
shown with an original pictogram and condition label. Conditions cover unknown,
clear, partly cloudy, cloudy, fog, drizzle, rain, snow and thunderstorms. Humidity
is displayed only when the weather snapshot supplies it; an observed `0%` is not
missing data.

The temperature dial is a fixed cyan-to-coral scale with a white marker, not a
forecast high/low range. Its visual range is **−30 to 50 °C**, equivalent to
**−22 to 122 °F**. Values outside that range saturate the marker without changing
the numeric reading. Longer extreme readings use a smaller existing font rather
than truncating the number. Location labels are bounded and may be ellipsized.

Built-in weather comes from the relay, not a separate device HTTP request.
See [weather configuration and provider terms](weather.md). Expired or missing
weather falls back through the existing controller policy; the UI does not keep a
stale value looking live.

## CPU

The outer segmented ring represents CPU usage and the thinner inner ring represents
memory usage. The center displays CPU percentage, while a separate **Memory** label
identifies the inner measurement. Colors progress from cyan/mint to amber/coral as
usage increases; the readings also identify values without relying on color.

A present zero memory value displays `Memory 0.00%`; an absent measurement displays
`Memory unavailable` with an unfilled ring. Optional CPU temperature is not used as
a substitute memory reading. Values come from a companion's measured telemetry;
the firmware does not generate a pretend load or silently refresh expired samples.

## Rendering verification and captures

The gauge renderers use fixed LVGL primitives and bounded label storage, with no
additional fonts. Generic adds bounded low-resolution runtime photo buffers rather
than bundled full-resolution raster assets. The existing real-LVGL memory-display
suite exercises widget selection, exact readings, gauge extremes, optional fields,
condition pictograms, circular label bounds and notification/freshness transitions.

To capture the actual RGB565 render output using test telemetry, create an output
directory and run from the repository root with the installed PlatformIO CLI:

```sh
mkdir -p "$HOME/twitch-screen-ui-captures"
TS_UI_CAPTURE_DIR="$HOME/twitch-screen-ui-captures" \
  .venv-pio/bin/pio test -d twitch-screen-firmware \
  -e native-lvgl-base -f test_base_view
```

The optional output consists of binary PPM frames marked as synthetic codec-test
telemetry. These are actual LVGL framebuffer renders, not a separate approximation
of the UI, but do not certify pixels on the physical LCD. A production build,
connected-board application reports and physical visual inspection are separate
verification layers.
