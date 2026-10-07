# Releasing F&F Thermal

## The signing key

Every release must be signed with the same key. Android refuses to
install an update signed with a different one, so a lost key means every
user has to uninstall - losing their settings and calibration - to move
to a build signed with a new key. Keep at least one backup of the key
file and its password somewhere other than the machine that builds.

The key is created once:

```
keytool -genkeypair -v -keystore /path/outside/the/repo/ff-thermal-release.jks \
    -alias ff-thermal -keyalg RSA -keysize 4096 -validity 10000
```

The build finds it through `keystore.properties` in the project root,
which git ignores. The file names the key; it does not hold the password:

```
storeFile=/path/outside/the/repo/ff-thermal-release.jks
keyAlias=ff-thermal
```

The password comes from the environment at release time, typed in from the
password manager without echo and without landing in the shell history:

```
read -rs FFT_KEYSTORE_PASSWORD; export FFT_KEYSTORE_PASSWORD FFT_KEY_PASSWORD=$FFT_KEYSTORE_PASSWORD
```

The key is a PKCS12 store, so the store and the key share one password.
Any of the four values can come from either place: `storeFile`,
`storePassword`, `keyAlias` and `keyPassword` in the file, or
`FFT_KEYSTORE`, `FFT_KEYSTORE_PASSWORD`, `FFT_KEY_ALIAS` and
`FFT_KEY_PASSWORD` in the environment. With no key named at all,
`assembleRelease` builds an unsigned APK. With a key named but no
password, it stops with "missing required property storePassword" rather
than ship something unsigned.

The release key was created on 2026-10-07: RSA 4096, alias `ff-thermal`,
certificate `CN=F&F Thermal, O=fluxfilament`, valid until 2054. A copy of
the key file and its password is kept in the maintainer's password
manager. Every published APK must carry this certificate:

```
SHA-256: bc:aa:c2:08:f5:cf:ae:b6:2a:ae:e9:a5:dc:65:f0:2d:2c:dc:2e:e7:45:c6:98:4e:fd:64:a1:74:d3:5a:93:db
```

A phone with a debug build installed will not take the release build over
it, because the two are signed with different keys. The debug build has
to be uninstalled first, and its settings go with it.

## A release, step by step

1. **Version.** In `app/build.gradle.kts`, raise `versionCode` by one and
   set `versionName`.
2. **Changelog.** Give the version's section in `CHANGELOG.md` today's
   date in place of "unreleased", and open a new "unreleased" section
   above it for what comes next.
3. **Check.** `./gradlew testDebugUnitTest lintDebug`; CI runs the same
   on every push. Install the release build on a phone with the camera
   plugged in and take one snapshot.
4. **Build.** Enter the password as above, run `./gradlew assembleRelease`,
   then confirm the certificate
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
