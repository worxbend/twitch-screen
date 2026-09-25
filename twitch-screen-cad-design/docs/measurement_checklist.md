# Hardware measurements before fabrication

The four supplied photos establish the configuration, not manufacturing scale.
All dimensions below are editable in `parameters.json` and the FreeCAD
`Parameters` object. **The nominal files are not a physical fit certification.**

| Item | Nominal model | Evidence / action |
| --- | --- | --- |
| LCD circular PCB / overall height | 37.5 / 40.4 mm | Waveshare official outline; confirmed |
| LCD tab width | 14.28 mm | Waveshare official outline; confirmed |
| LCD active diameter | 32.4 mm | Waveshare official specification; confirmed |
| Visible opening | 33.2 mm | Design choice; gives 0.4 mm radial margin around active area |
| LCD glass diameter / thickness | 35 / 1.8 mm | Measure glass and its offset to PCB |
| LCD PCB thickness | 1.6 mm | Measure; affects retainer's contact plane |
| LCD connector envelope | 21 × 6.5 × 6 mm | Measure with supplied cable plugged in; inspect cable exit |
| LCD connector centre | v = +10 mm | Provisional; rotate module so tab points down and connector up |
| LCD brass mount pattern / envelope | 27 × 19 mm; Ø4 × 1.2 mm projection | Provisional clearance model only; measure all four mounts. Retainer has Ø6 reliefs, not mating screw holes |
| ESP32 PCB outline | 55 × 28 × 1.6 mm | Measure exact Type-C board, excluding connector projection |
| ESP32 hole centres / bore | 24 × 49 mm / 3 mm | Measure centre-to-centre and diameter; four visible holes |
| Header row spacing / length | 25.4 / 38.1 mm | Measure actual soldered headers |
| Header + female housing depth | 11 mm below PCB | Measure fully installed jumper housing and allow wire bend |
| USB socket envelope | 9 × 7.5 × 3.4 mm | Measure width, length, height and vertical offset |
| USB beyond PCB rear edge | 2.5 mm | Measure socket's mating face, not shield pad extent |
| USB horizontal offset | 0 mm | Measure relative to PCB centreline |
| USB lower face above PCB top | 0 mm | `UsbBottomOffset`; measure the socket's vertical seating |
| Cable overmould | 14 × 9 mm | Measure the intended USB cable; port is generated from this envelope |
| Overmould corner radius | 2 mm | Rounded rectangular port; measure actual cable profile, set `UsbPlugCornerRadius` to 0 for a square envelope |
| Tallest ESP32 components | shield 3.2 mm above PCB; buttons 2.5 mm | Verify other components do not exceed these local envelopes |
| Shield / buttons XY envelopes | 18 × 25 mm shield, 4 × 4 mm buttons | Measure widths, depths and offsets; all have named parameters |
| Eight-wire loom corridor | Ø4.4 mm | Measure bundle and confirm bends are gentle enough for the actual wires |
| Screw pilot fit | Ø2.1 mm for M2.5; Ø1.7 mm for M2 | Print test holes in the chosen filament; adjust pilot dimensions in geometry if needed |

The retainer grips the LCD PCB perimeter. Its two mounting screws attach to the
enclosure, **not** the LCD's four brass mounting points. Their undocumented
thread size is not an enclosure fastening assumption. Their location and
projection still require measurement to confirm the generous retainer reliefs.

To update: edit parameters, rebuild, run the validation scripts and inspect
the new section render. Within FreeCAD, open `TwitchScreen.FCMacro`, edit the
Parameters object, and recompute. Exports do not update merely by saving FCStd;
copy changed dimensions to `parameters.json` and run the export command.

Print a base and check the ESP32 hole pattern first. Check the bezel opening,
glass seat and connector clearance before tightening the retainer. Use light
torque; no metal screw or printed edge should load the active glass surface.
