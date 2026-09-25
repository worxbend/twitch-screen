============================================================
0. PROJECT INPUTS AND REFERENCE IMAGES
============================================================

This project directory contains reference images showing the desired appearance,
proportions, orientation, and industrial-design language of the enclosure.

Before writing CAD code:

1. Recursively inspect the project directory.
2. Identify all reference images:
   - *.jpg
   - *.jpeg
   - *.png
   - *.webp
3. Inspect EVERY relevant image.
4. Classify each image as one or more of:
   - physical hardware reference
   - desired enclosure appearance
   - desired shape/proportions
   - assembly/layout reference
   - previous concept/blueprint
5. Build an explicit understanding of the device before implementing geometry.

The images have TWO fundamentally different purposes.

PHYSICAL HARDWARE PHOTOS
------------------------

Photos showing my actual:

- Waveshare 1.28" round LCD
- ESP32 DevKit V1 Type-C
- mounting holes
- connectors
- PCB geometry
- pin headers
- buttons

must be used to identify the EXACT hardware configuration I own.

However, photographs are NOT sufficiently accurate for extracting critical
manufacturing dimensions because of perspective/lens distortion.

Use official mechanical drawings/datasheets where available and expose uncertain
dimensions as parameters requiring measurement.

DESIGN REFERENCE IMAGES
-----------------------

Other images show the desired enclosure appearance.

Use these as the primary INDUSTRIAL DESIGN reference.

Study:

- overall silhouette
- tilted circular face
- wedge/pod proportions
- curvature
- transitions between surfaces
- bezel treatment
- base shape
- front/rear visual balance
- edge radii
- apparent display-to-body ratio
- minimalist consumer-electronics appearance

Reproduce the DESIGN LANGUAGE, not arbitrary dimensions visible in the image.

The desired result is approximately:

    reference-image appearance
             +
    actual hardware constraints
             +
    mechanically valid construction
             =
    final enclosure

If the reference appearance conflicts with physical hardware requirements,
preserve the visual intent while modifying dimensions sufficiently to make the
device mechanically valid.

============================================================
0.1 DO NOT HALLUCINATE FROM REFERENCE IMAGES
============================================================

Never assume that a generated/reference concept image represents valid internal
mechanics.

For example, do NOT copy:

- fake PCB positions
- impossible mounting bosses
- invented dimensions
- incorrect USB locations
- impossible cable routing
- incorrect component thicknesses
- arbitrary screw positions

Reconstruct the mechanical architecture independently.

The images answer:

    "What should the finished product look like?"

The hardware models answer:

    "What geometry must actually fit inside?"

The parametric CAD model must reconcile both.

============================================================
0.2 REQUIRED IMAGE-ANALYSIS PHASE
============================================================

Before implementation, create:

    docs/reference_analysis.md

Document:

1. Which files were inspected.
2. Which show real hardware.
3. Which show desired enclosure design.
4. Observed design characteristics.
5. Hardware constraints visible in photographs.
6. Dimensions verified from authoritative sources.
7. Dimensions still requiring measurement.
8. Assumptions being made.
9. Proposed internal component arrangement.

Also create a simple coordinate-system convention:

    +X = device right
    +Y = device rear
    +Z = upward

Define explicitly:

    FRONT = display side
    BACK  = USB-C side

This convention must be used throughout the CAD project.

============================================================
0.3 NON-NEGOTIABLE ORIENTATION
============================================================

The reference images may contain earlier concepts with incorrect USB placement.

IGNORE those mistakes.

For THIS design:

              FRONT
                ↓

          /  ROUND LCD  /
         /              /
        /               /
       /                \
      /                  \
     +--------------------+
              ESP32
                 |
                 |
              USB-C
                 ↓
               BACK

The ESP32 USB-C connector faces the BACK of the device.

The enclosure USB-C opening is on the BACK.

There must be NO USB-C opening on the front.

The rear USB opening must be GENERATED FROM the transformed position of the
ESP32 USB-C connector, rather than independently positioned.

This requirement overrides conflicting details in any reference image.
