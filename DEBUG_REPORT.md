# Deep Debug Report

## Issues corrected

1. **Build tool compatibility:** AGP was changed to 8.7.2 to stay within the fully supported AGP range for Kotlin Gradle Plugin 2.1.20. Gradle 8.9 meets AGP 8.7's minimum and Kotlin 2.1.20's supported Gradle range.
2. **Incorrect software fallback:** Removed the unconditional `libx264` fallback. The app now chooses from encoders actually listed by the FFmpeg runtime and falls through `h264_mediacodec` → `libopenh264` → `mpeg4` as available.
3. **Hardware runtime failures:** Encoder discovery alone is not treated as proof of device support. A failed MediaCodec output step is retried using the next available software encoder.
4. **Command checking:** Every FFmpeg command's return code is checked. The app no longer carries on after an unsuccessful intermediate encode/concat step.
5. **Temporary files:** Processing workspace cleanup is in `finally`; abandoned work folders are cleaned on the next run. Interrupted output backups are restored if a run was killed mid-replacement.
6. **Output safety:** The new output is staged before replacing an older same-name result; a backup is used to restore the previous file if replacement fails.
7. **Cancellation:** Stop requests cancel FFmpeg and suppress automatic hardware fallback after a user cancellation.
8. **Pending output:** The latest unexported result path is stored in app preferences so it remains available after an activity/app restart.
9. **UI thread safety:** Processing status mutations are dispatched back to the main thread.
10. **Storage:** SAF is used for user-selected input/output. Work data is stored in app-scoped Movies storage, with a fallback to internal app storage.
11. **Input validation:** Speed factor and audio boost must be finite and positive/non-negative respectively; join count must be at least one when processing rounds are enabled (zero is allowed when rounds are zero); repeat count may be zero as in the notebook's loop behavior.
12. **Audio factor edge cases:** `atempo` is split into valid 0.5–2.0 stages for both high and low factors before applying volume.
13. **Native ABI:** Restricted packaging to `arm64-v8a`, because the selected maintained FFmpegKit 8.1 artifact publishes arm64-v8a only; an x86_64 filter would falsely imply emulator support.
14. **Launcher icon:** Added density-specific legacy PNGs and Android 8+ adaptive icon resources.

## Tests run

- Kotlin utility tests: `2^10 = 1024`, notebook-style scientific display for `20^10`, zero rounds = 1; tempo factor 1000 chain and 0.1 chain validated.
- Local FFmpeg 7.1.5 integration test: generated a 2-second 320×240 video with AAC audio; ran two rounds with speed factor 2 and two joins per round. Final video stream duration was approximately 2.058 seconds and AAC stream duration approximately 2.113 seconds due to codec frame padding. The repeated video content/duration behavior matched the expected recursive sequence.
- XML parsing: AndroidManifest and adaptive launcher icon XML.
- ZIP inspection: all source and resource files present.

## Not tested

- Full Android Gradle compilation, because Gradle and Android SDK are absent from this environment.
- Runtime MediaCodec behavior on a real Android device; actual codec support, profiles, and bitrate behavior vary by manufacturer and chipset.
- Long processing while the app is backgrounded; this version keeps the screen awake but does not use a foreground service.
