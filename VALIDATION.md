# Portfolio export validation

Checked October 7, 2026 against this source export.

| Check | Result |
| --- | --- |
| Kotlin/JVM debug unit tests across app and runtime modules | 119 passed; no failures or skips |
| Native Libretro boundary/session harnesses | 2 passed |
| CoreLab synthetic certification tests | 2 passed |
| Full Android debug and unsigned release builds | Passed for arm64-v8a and x86_64 |
| Debug and release lint | Zero errors; 10 warnings in each lane |
| 16 KB native ELF load alignment and APK ZIP alignment | Passed for both APKs |
| Installed-app emulator journeys, Android 36 arm64 | 9 passed |

The device journeys exercise original diagnostic GB content with real SameBoy rendering, save/load, Activity recreation, automatic recovery, native-save failure/retry, backup round trips, and simulated fold/controller transitions. Activity recreation is not process-death qualification. Physical foldable and controller behavior, long-duration testing, release signing, vendor compatibility, and store distribution remain separate milestones.

Lint warnings concern newer dependency/tool versions and an attribute ignored below its supported API. Dependency updates require separate compatibility testing.

Reproduce portable tests through GitHub Actions or the README's CMake commands. Use the README's Android build and device-journey commands for Android checks. `tools/verify_android_artifact.sh` verifies both ABI coverage and native/ZIP alignment. No signing keys, runtime user data, operational logs, or commercial ROMs are part of this source export.


## Publication safeguards

The publication guard's 10 synthetic-history tests pass, including author/committer fallback, secrets deleted from later commits, commit-message disclosures, generated files, private path/address patterns, symlinks, and changed reviewed-binary digests. Both the full public history and source exports pass the guard. Gradle wrapper digests match official release checksums and distribution digests are pinned.
