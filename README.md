# FLIR One Viewer (unofficial)

Unofficial, open-source Android app for the **FLIR One for Android**
thermal camera (USB-C model). Talks to the camera directly over USB and
reads the sensor at its native resolution, with access to the raw
counts behind the picture.

**Not affiliated with or endorsed by Teledyne FLIR.**

## Status

Early development. The USB protocol is verified against real hardware,
and the decode pipeline (de-interleave → drop shutter-calibration frames
→ colourise → live view) is in place. Still to come for v1: snapshot
saving and temperature readout. No gallery or thermal/visible overlay —
planned for later.

### Sensor resolution, stated plainly

The FLIR One for Android (Gen 3) carries a **Lepton 2 at 80×60**. The
FLIR One **Pro** carries a Lepton 3 at 160×120. This app does not
"unlock" a higher resolution on either — it reads whatever the camera
reports in its `sledInformation` message and decodes that, so it works
on both without a hardcoded size. Claims elsewhere that the official app
halves a 160×120 sensor do not apply to the 80×60 hardware.

## Why this exists

The third-party app that exposed the camera's raw sensor data (Thermal
Camera+ for FLIR One, by Georg Friedrich) was pulled from Google Play,
leaving the official app as the only option on current Android. This
project is an open replacement, building on two existing open-source
reverse-engineering efforts for the FLIR One's USB protocol (see
[NOTICE](NOTICE) for full credit):

- [fnoop/flirone-v4l2](https://github.com/fnoop/flirone-v4l2)
- [Miso98/hw-flir-one-gen3](https://github.com/Miso98/hw-flir-one-gen3)

## Accuracy disclaimer

The live view is currently raw sensor counts with per-frame auto-gain:
it shows *hotter and colder*, not *how hot*. No temperature is displayed
yet, and that is deliberate — converting counts to degrees needs the
Planck-equation coefficients, which the upstream projects extracted from
one sample unit's JPEG EXIF metadata rather than reading at runtime.
Once temperature readout lands, treat it as indicative, not as a
calibrated or certified measurement, especially on a physical unit other
than the ones those constants were sampled from.

## Building

Requires JDK 17+, Android SDK (compileSdk 37), and the Gradle wrapper
(bundled, no local Gradle install needed once `gradlew` exists):

```
./gradlew assembleDebug
```

Install on a connected device/phone with the camera attached via USB-C
OTG:

```
./gradlew installDebug
```

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE) (the
NOTICE file credits the upstream protocol reverse-engineering this
project is built on, as required by the GPL).

## Support

If you find this useful, donations are welcome via [link TBD] — entirely
optional, no feature is gated behind it.
