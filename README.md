# FLIR One Viewer (unofficial)

Unofficial, open-source Android app for the **FLIR One for Android**
thermal camera (USB-C model). Reads the sensor's full native resolution
and raw radiometric data directly over USB — unlike the official FLIR ONE
app, which crops the live view.

**Not affiliated with or endorsed by Teledyne FLIR.**

## Status

Early development (v1 in progress): live view + snapshot saving. See
[open issues](../../issues) for what's tracked. No gallery, palette
selection, or thermal/visible overlay yet — planned for later.

## Why this exists

The only third-party app that exposed the full sensor resolution
(Thermal Camera+ for FLIR One, by Georg Friedrich) was pulled from
Google Play. This project reimplements that capability from scratch,
building on two existing open-source reverse-engineering efforts for the
FLIR One's USB protocol (see [NOTICE](NOTICE) for full credit):

- [fnoop/flirone-v4l2](https://github.com/fnoop/flirone-v4l2)
- [Miso98/hw-flir-one-gen3](https://github.com/Miso98/hw-flir-one-gen3)

## Accuracy disclaimer

Temperature-conversion constants (the Planck-equation coefficients) were
extracted from a specific sample unit's JPEG EXIF metadata by the
upstream projects, not read from this app's own hardware at runtime.
Absolute temperature readings may be inaccurate, especially on a
different physical unit than the ones those constants were sampled from.
Treat readings as indicative, not calibrated/certified measurements.

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
