Fixtures (not synthetic provider acceptance)

photo96.jpg was downloaded directly from https://picsum.photos/96/96 using curl
with its normal certificate/hostname validation. It is a 2732-byte 96x96 SOF2
progressive JPEG. It is not the parent's 240x240 sample, not resized locally,
not baseline-transcoded, and no relay was involved.

weather.json is an exact copy of the parent-saved Open-Meteo provider document
in .agent-workflow/standalone-generic/open-meteo-weather.json. Native rendering
sets local receipt time explicitly; this fixture is not evidence of live weather.

photo96.rgb565 is little-endian RGB565 generated from photo96.jpg using the
pristine official stb_image.h pinned at upstream commit
2c980bb59875b0d32144a71867fbdebb2f77cd20, STBI_ONLY_JPEG / STBI_NO_STDIO /
STBI_NO_SIMD, req_comp=3 and RGB565 truncation. Every one of its 9216 pixels
is compared against the budgeted locally patched production decoder.

FaultTransport responses in test_generic_view.cpp are explicitly synthetic
negative protocol/deadline/ownership tests, not public-network acceptance.

Run from twitch-screen-firmware:
  TS_UI_CAPTURE_DIR=/absolute/capture/directory pio test -e native-generic -v

PPM files are real memory-display LVGL flushes. They prove native rendering of
actual downloaded provider fixtures, not the physical GC9A01 or device TLS.
