# Reference analysis — before CAD implementation

The initial recursive inspection found four hardware photos, all opened on
2026-09-25. A final scan found five newly added enclosure screenshots; all were
opened and the model was revised to incorporate them. PLAN.md is the supplied
written plan. Both the initial hardware study and later design study are below.

| File | Classification | Observations |
| --- | --- | --- |
| `refs/hardware/1000011984.jpg` | Physical hardware, underside | ESP32 DEVKIT V1 TYPEC label; two populated header rows; four corner mounting holes; connector at one short end. |
| `refs/hardware/1000011986.jpg` | Physical hardware, component side | 30 pins (15 per side), ESP-32 metal shield, PCB antenna at opposite end from Type-C, CP2102-family bridge marking, EN and BOOT buttons beside USB. Manufacturer/revision cannot be established from visible markings. |
| `refs/hardware/1000011987.jpg` | Physical hardware, LCD rear / wiring | Waveshare 1.28inch LCD Module 240×240, eight-way side-entry connector with installed loom, four brass mounting points; board is round with a tab. |
| `refs/hardware/1000011988.jpg` | Physical hardware, LCD front | Circular glass, flat/tab at the ribbon end, protective film still attached; cable assembly visible. |
| `refs/concepts/screenshot_2026-09-25_06-08-08.png` | Previous concept/blueprint; shape, proportions and orientation reference | Front round display, tapered curved pod, 60° face concept, side 3×3 vents, rear horizontal slots/USB, low removable base and feet. Printed 60×55×62 dimensions are concept annotations, not hardware measurements. |
| `refs/concepts/screenshot_2026-09-25_06-08-21.png` | Desired appearance and previous detail concept | Light smooth shell, thin light outer rim, dark circular face, low dark base, rear horizontal vents, side perforations. USB, vent and boss dimensions are design examples, not verified owned-board dimensions. |
| `refs/concepts/screenshot_2026-09-25_06-08-40.png` | Concept section / internal layout reference | Display above a horizontal ESP32 with wiring space and screw base. The depicted 51 mm board, 8 mm posts, LCD stack and mount layout are not copied as facts; photographed Type-C hardware and current fit model govern. |
| `refs/concepts/screenshot_2026-09-25_06-09-02.png` | Desired frontal appearance; conflicting previous orientation | Large dark face with fine light rim and a subtle base seam. Front USB is explicitly rejected by PLAN.md. |
| `refs/concepts/screenshot_2026-09-25_06-09-10.png` | Base appearance / assembly concept | Rounded footprint, four circular feet, six underside ventilation slots and understated perimeter seam. |

At first only the written industrial-design brief was available. The final
design now incorporates all five added screenshots: a dark circular face insert
with a 1.15 mm light outer rim, sloping curved pod, 3×3 side perforations, three
rear horizontal slots, six underside slots, four foot recesses and a dark base
seam. The rear-only USB rule takes precedence over the conflicting frontal image.

The real 32.4 mm active LCD area cannot fill a concept's roughly 50 mm black
face. A thin black printed fascia surrounds the actual display instead of
enlarging or inventing the LCD. The 64 × 86 × 78.1 mm enclosure is larger than
the concept because the provisional 55 mm Type-C board, downward headers,
plug, screw heads and wire housings need real clearance. The conceptual 55 mm
depth cannot enclose a 55 mm PCB plus connector and walls. The 55° face is an
adjustable design choice close to the concept's tilted display language.

## Coordinates

Millimetres. +X device right, +Y device rear, +Z upward. FRONT is the display
side (negative Y); BACK is USB-C side (positive Y). The display local axes are
u = +X, v = up the tilted face, n = outward toward the viewer. Rotate local
coordinates about +X by the face angle. The ESP32 is horizontal with USB toward
+Y and its antenna toward -Y. USB cutout comes from the connector transform.

## Authoritative dimensions and uncertainty

[Waveshare's official product documentation](https://docs.waveshare.net/1.28inch_LCD_Module/)
identifies SKU 19192, GC9A01, active display diameter 32.4 mm, overall module
40.4 × 37.5 mm, 240×240 resolution and eight SPI/power signals. These dimensions
are used without estimating scale from photographs.

No authoritative mechanical drawing for the exact ESP32 Type-C clone has been
identified. Do not substitute an Espressif DevKitC or micro-USB DOIT outline.
Provisional starting envelope: 55 × 28 × 1.6 mm; all connector positions,
heights, mounting-hole centres, diameter, pin projection and component heights
must be measured on the user's board. Likewise LCD stack thickness, rear
connector envelope, mounting-thread size and hole pattern need verification.
The CAD parameter definitions and measurement checklist distinguish these
assumptions from verified dimensions. The output is a complete nominal CAD
design; physical fit remains conditional on those measurements.

The official [dimensioned outline image](https://www.waveshare.com/img/devkit/LCD/1.28inch-LCD-Module/1.28inch-LCD-Module-details-size.jpg)
was subsequently retrieved and visually inspected as `refs/datasheets/waveshare_dimensions.jpg`.
It confirms the 37.50 mm circular outline, 40.40 mm overall length and 14.28 mm
tab width (3.59 mm lower tab segment). It does not dimension hole centres or
thickness. `refs/datasheets/waveshare_rear.jpg` was also inspected; despite the filename,
it is the official development-board wiring/use photograph, not another owned
hardware photo or an enclosure design reference. It confirms a flexible loom.

## Proposed arrangement and design assumptions

- 55° display plane from horizontal, circular bezel on a smooth lofted pod.
- Horizontal ESP32 on four base standoffs; header pins face downward with space
  for female jumper housings. EN/BOOT are accessible after removing the base.
- LCD behind the integral front bezel, retained from inside by a removable
  peripheral ring; connector, tab and four brass mounts receive explicit
  clearance. Brass mount envelopes/pattern remain provisional parameters;
  the retainer does not fasten into their undocumented threads.
- Eight-wire harness routes through the side cavity to the downward headers;
  keep slack to separate base from shell during servicing.
- USB insertion direction +Y, rear opening projected from the board's actual
  connector location. A plug-overmould keepout is checked as well as the socket.
- Shell and base close with recessed underside screws into pilot-hole bosses;
  no front USB port. Small replaceable feet are purchased adhesive parts.
- A 0.8 mm matte black fascia is attached with 0.15 mm double-sided adhesive;
  the original 1.5 mm structural bezel remains intact underneath it. This keeps
  the thin-rim appearance without making the LCD or the shell wall fictitious.
- Printed parts are nominal PETG, 0.4 mm nozzle, with assembly clearance exposed
  as a parameter. These are manufacturing choices, not measured hardware facts.
