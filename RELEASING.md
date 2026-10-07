# Releasing F&F Thermal

## The signing key

Every release must be signed with the same key. Android refuses to
install an update signed with a different one, so a lost key means every
user has to uninstall - losing their settings and calibration - to move
to a build signed with a new key. Keep at least one backup of the key
file and its passwords somewhere other than the machine that builds.

The key is created once:

```
keytool -genkeypair -v -keystore /path/outside/the/repo/ff-thermal-release.jks \
    -alias ff-thermal -keyalg RSA -keysize 4096 -validity 10000
```

The build finds it through `keystore.properties` in the project root,
which git ignores:

```
storeFile=/path/outside/the/repo/ff-thermal-release.jks
storePassword=...
keyAlias=ff-thermal
keyPassword=...
```

The same four values can come from the environment instead
(`FFT_KEYSTORE`, `FFT_KEYSTORE_PASSWORD`, `FFT_KEY_ALIAS`,
`FFT_KEY_PASSWORD`). Without either, `assembleRelease` still builds, but
the APK is unsigned.

Release certificate, SHA-256: *not created yet.*

## A release, step by step

1. **Version.** In `app/build.gradle.kts`, raise `versionCode` by one and
   set `versionName`.
2. **Changelog.** Give the version's section in `CHANGELOG.md` today's
   date in place of "unreleased", and open a new "unreleased" section
   above it for what comes next.
3. **Check.** `./gradlew testDebugUnitTest lintDebug`; CI runs the same
   on every push. Install the release build on a phone with the camera
   plugged in and take one snapshot.
4. **Build.** `./gradlew assembleRelease`, then confirm the certificate
   is the one above:

   ```
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

5. **Tag.**

   ```
   git tag -a v0.1.0 -m "F&F Thermal 0.1.0"
   git push origin v0.1.0
   ```

6. **GitHub Release.** Attach the APK under a name that says what it is,
   with the changelog section as the notes:

   ```
   cp app/build/outputs/apk/release/app-release.apk ff-thermal-0.1.0.apk
   gh release create v0.1.0 ff-thermal-0.1.0.apk --title "F&F Thermal 0.1.0" --notes-file notes.md
   ```

## F-Droid, later

F-Droid builds from source and signs with its own key unless the build
is reproducible, so its APK and the GitHub one cannot update each other:
switching between them means reinstalling. The build already leaves out
AGP's encrypted dependency block, which F-Droid's scanner rejects.
