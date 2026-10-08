# Android integration tests

These tests use an original diagnostic fixture in `assets/diagnostic-battery-gb.hex`, a test-only content provider, and the real native runtime. They cover import, playback, save/load, Activity recreation, controller removal, backup rejection, and layout behavior.

The fixture contains diagnostic code rather than a commercial game. Tests isolate their diagnostic content and restore preferences. Activity recreation is not process-death coverage; simulated folding features are not physical-hinge qualification.

## Run

With the Android SDK, required core build tools, and a configured device or emulator:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Gradle writes test reports under the app's generated build directory. This portfolio snapshot's published portable CI does not run Android device tests. See the root `VALIDATION.md` for checks actually rerun against the export.
