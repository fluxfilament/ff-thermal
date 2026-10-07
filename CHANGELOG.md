# Changelog

All notable changes to F&F Thermal. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [0.1.0] - unreleased

First public release.

### Camera
- Talks to the FLIR ONE gen 3 (USB-C) directly over USB, without FLIR's
  SDK. The sensor size comes from the camera, not from the code.
- Picks the stream back up when the camera is plugged in while the app
  is already open.

### Measuring
- Live view at the sensor's own resolution, about 9–10 frames per
  second; minimum, centre and maximum in the status line.
- Up to nine spot meters, dragged with a finger, each averaging 3×3
  pixels.
- Emissivity from 0.10 to 1.00.
- Automatic range, or a fixed window in °C with a scale bar under the
  picture.
- FLIR's radiometric model in full: Planck constants, emissivity,
  reflected temperature, the lens window and the air. Matches the
  official app within 0.2 °C on the development unit's reference shots.
- Calibration: import a camera's own constants from a JPEG saved by the
  official app, then correct readings against melting ice, boiling water
  or a contact thermometer.

### Picture
- Visible-light overlay from the camera's second lens: off, 35 %, 60 % or
  visible only, with field-of-view and alignment adjustment.
- Grayscale and iron palettes.
- Rotation and mirroring for either way the camera is plugged in; the
  interface turns 180° with the phone.

### Snapshots
- A card with the picture, spots, scale and settings, plus the bare
  sensor picture beside it, saved to `Pictures/FFThermal`.

### Everything else
- English and Russian interface.
- No permissions and no network access.
- Settings never go to the cloud backup. A direct transfer to a new phone
  carries them, camera constants and calibration included.

### Known limitations
- Tested on one camera. The FLIR ONE Pro (160×120) should work but has
  never been run.
- No gallery yet: snapshots are opened from the phone's own gallery.
