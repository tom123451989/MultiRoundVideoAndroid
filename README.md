# Multi-Round Video Speed-Up + Join — Android

A Kotlin + Jetpack Compose Android port of the uploaded Colab notebook. The processing sequence remains:

1. Copy the selected source video into an app-private temporary work folder.
2. For each full repeat round, apply `setpts=PTS/speed_factor` and (when enabled) an audio `atempo` chain plus volume boost.
3. Re-encode and concatenate the processed clip `join_times` times.
4. Feed that joined file into the next round.
5. Name the result `<source-base>_final_<full-repeats>x.mp4`.
6. Clean up temporary work files and let the user export the result using Android's Save document picker.

The repetition summary uses `join_times ^ full_repeats`. `full_repeats = 0` is supported and copies the original input unchanged, matching the notebook's empty loop behavior.

## Android GUI

- Modern dark Material 3 / Jetpack Compose screen with matching system-bar colors.
- Android document picker for source video selection.
- Editable speed factor, join count, full repeat count, include-audio switch, and audio boost percentage.
- Effective repetition calculation with large-number formatting.
- Processing status and round/step progress.
- Start / Stop controls.
- Output Save picker; generated output remains in app storage if the user cancels the picker, and its pending path is remembered across app restarts.
- Launcher icon assets for legacy and adaptive Android launchers.

## Video encoder selection

The app probes the installed FFmpeg encoder list at runtime. It prefers Android `h264_mediacodec` hardware encoding. If an actual MediaCodec encode fails, it retries the same step with an available software encoder. Fallback order is `libopenh264` when exposed by the build, then FFmpeg's built-in `mpeg4` encoder. It deliberately does not assume `libx264` exists in the selected LGPL package: x264 is in the separately licensed GPL FFmpegKit variants.

The selected dependency is `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (verified on Maven Central). The maintained FFmpegKit fork documents MediaCodec support in its Android packages and provides the same `com.arthenica.ffmpegkit` API. Hardware encoder availability is still device-specific; a successful encoder-list probe does not guarantee that the phone can encode every input profile, which is why a real encode failure triggers fallback.

## Storage, cancellation, and recovery

- Source files are read through Android's Storage Access Framework; no broad storage permission is requested.
- Work and staged output live in the app's external Movies folder (with an internal-storage fallback).
- The app checks for a minimum free-space reserve before starting.
- Temporary work is deleted in `finally`, including after FFmpeg errors and user cancellation.
- A completed but not-yet-exported output is remembered across app restarts so the user can still save or discard it.
- A completed result is staged before replacing an older same-name output, so a failed run does not erase the previous valid result.
- On the next run, abandoned processing folders are removed and interrupted output backups are restored when necessary.
- The screen is kept awake while processing. Keep the app in the foreground during a long run; this build does not implement a foreground-service background worker.

## Build requirements

- Android Studio with Android SDK Platform 35 installed.
- JDK 17.
- A local Gradle 8.9 installation. After installing Gradle, you can generate the standard wrapper with `gradle wrapper --gradle-version 8.9`.
- Android Gradle Plugin 8.7.2, Kotlin 2.1.20, Gradle 8.9.
- Android target API 35, minimum API 24.
- The selected FFmpegKit 8.1 artifact publishes `arm64-v8a` only, so the app is intentionally filtered to `arm64-v8a`. Use a physical 64-bit Android phone or an ARM64 emulator image; an x86_64-only emulator is not supported by this native package.

Open the project folder in Android Studio and configure it to use your local Gradle 8.9 installation, allow Gradle sync, then use **Build → Build APK(s)**. `settings.gradle.kts` includes Google Maven and Maven Central repositories. This archive does not include standard Gradle Wrapper scripts/JAR because no Gradle installation was available here to generate and validate them. Alternatively, run `BUILD_WINDOWS.bat` from Windows after adding Gradle 8.9 to `PATH`.

## Verification status

What was verified in the available build container:

- Pure Kotlin repetition-formatting and audio-tempo utility tests passed.
- The multi-round FFmpeg command sequence was integration-tested with a generated video/audio sample using the built-in MPEG-4 fallback: speed-up → join → recursive second round → MP4 output. The expected repeated-content duration was preserved; AAC stream duration differs slightly from video duration because of AAC frame padding/time-base rounding.
- Android manifest and adaptive-icon XML were parsed successfully.
- Project archive contents were checked.

What could not be verified here:

- A full Android Gradle build or APK install/run. This environment does not have Gradle or the Android SDK installed, so no claim is made that the APK has been compiled or tested on a phone.
- Actual MediaCodec encoding on a physical Android device. The app probes it and falls back at runtime, but device testing is still required.

No pause/resume control is included because the supplied Colab notebook did not have pause/resume functionality. Stop is included as an Android usability control.
