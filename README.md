# F&F Thermal — for FLIR ONE

[![CI](https://github.com/fluxfilament/ff-thermal/actions/workflows/ci.yml/badge.svg)](https://github.com/fluxfilament/ff-thermal/actions/workflows/ci.yml)

Open-source Android app for the **FLIR ONE for Android** thermal camera
(gen 3, USB-C). It talks to the camera directly over USB, without FLIR's
SDK, and reads the sensor at its native resolution.

**Not affiliated with or endorsed by Teledyne FLIR.** FLIR and FLIR ONE
are trademarks of Teledyne FLIR; they are used here only to say which
camera the app works with.

## What it does

- **Live view** at the sensor's own resolution (80×60 on the gen 3),
  about 9–10 frames per second. The size is read from the camera, not
  hardcoded.
- **Temperatures in °C.** Up to nine spot meters you drag with a finger,
  each averaging 3×3 pixels. Minimum, centre and maximum are in the
  status line.
- **Emissivity** from 0.10 to 1.00.
- **Temperature range:** automatic, or a fixed window in °C, with a
  scale bar under the picture.
- **Visible-light overlay** from the camera's second lens: off, 35 %,
  60 % or visible only, with field-of-view and alignment adjustment.
- **Snapshots.** Each press saves a card to `Pictures/FFThermal` with the
  picture, spots, scale and settings on it. The bare sensor picture,
  nothing drawn over, goes to `Pictures/FFThermal/raw` under the same
  name.
- **Calibration.** Import your camera's own constants from a JPEG saved
  by the official app, then correct readings against melting ice,
  boiling water or a contact thermometer.
- Palettes: grayscale and iron. Interface in English and Russian.
- **No permissions and no network.** Snapshots go through the system
  media store, and a JPEG for calibration is opened through the system
  file picker, one file at a time.

## What has been tested, and what has not

| | |
|---|---|
| Tested | one FLIR ONE gen 3 (USB-C) on a Samsung Galaxy S21 |
| Not tested | FLIR ONE **Pro** (Lepton 3, 160×120): the frame size comes from the camera, so it should work, but it has never been run |
| Unknown | other FLIR ONE models with the same USB ID (`09cb:1996`) |
| Not supported | iPhone and Lightning models |

## Accuracy

The app converts sensor counts to temperature with FLIR's own radiometric
model: the Planck equation with emissivity, reflected temperature, the
protective window in front of the lens and the air. Its constants differ
from one camera to the next.

- **Built-in constants belong to the unit this app was developed on.**
  On another camera, readings can be off by degrees until you load your
  own. Save any picture with the official FLIR ONE app, then open
  *Calibration → Camera constants → From snapshot* and pick that JPEG.
- On the development unit, the model matches the official app within
  0.2 °C on three reference shots: melting ice at emissivity 0.60 and
  0.95, and boiling water. `PlanckTest` keeps it there.
- Treat readings as indicative, not as a certified measurement. The
  camera's own accuracy limits still apply, and emissivity matters more
  than anything else: bare metal reads tens of degrees off at the
  default.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37). The Gradle wrapper
is bundled.

```
./gradlew assembleDebug        # build
./gradlew installDebug         # install on a connected phone
./gradlew testDebugUnitTest    # unit tests, no camera needed
```

What changed between versions is in [CHANGELOG.md](CHANGELOG.md); how a
release is built and signed is in [RELEASING.md](RELEASING.md).

`tools/fff_parse.py` prints a camera's constants from a JPEG saved by
the official app, without exiftool. It leaves out the serial number.

## Credits and license

GPL-3.0-or-later, see [LICENSE](LICENSE). The USB protocol work builds on
two GPL-2.0-or-later reverse-engineering projects, credited in full in
[NOTICE](NOTICE):

- [fnoop/flirone-v4l2](https://github.com/fnoop/flirone-v4l2)
- [Miso98/hw-flir-one-gen3](https://github.com/Miso98/hw-flir-one-gen3)
