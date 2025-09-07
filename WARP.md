# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

Project: Android app with Kotlin + Jetpack Compose UI and an embedded native (CMake/NDK) library wrapping whisper.cpp for on-device speech-to-text. Multiple product flavors control billing/update behavior.

Common commands
- PowerShell on Windows uses .\gradlew.bat. On macOS/Linux, use ./gradlew.

Build

```powershell path=null start=null
# Fast dev debug build (app module, dev flavor)
.\gradlew.bat :app:assembleDevDebug

# Dev release APK (used in CI)
.\gradlew.bat :app:assembleDevRelease

# Standalone release APK (no Play billing, includes auto-update)
.\gradlew.bat :app:assembleStandaloneRelease

# Play Store release bundle (AAB) and other releases
.\gradlew.bat :app:bundlePlayStoreRelease
.\gradlew.bat :app:assembleFDroidRelease

# Clean
.\gradlew.bat clean
```

Test

```powershell path=null start=null
# JVM unit tests for devDebug variant (CI parity)
.\gradlew.bat :app:testDevDebugUnitTest

# Run a single test class
.\gradlew.bat :app:testDevDebugUnitTest --tests FeatureExtractorTest

# Run a single test method
.\gradlew.bat :app:testDevDebugUnitTest --tests FeatureExtractorTest.featureExtractor_TestLinspace

# Instrumented tests on a connected device/emulator
.\gradlew.bat :app:connectedDevDebugAndroidTest
```

Lint

```powershell path=null start=null
# Android Lint for all variants
.\gradlew.bat :app:lint

# Lint a specific variant (example: devDebug)
.\gradlew.bat :app:lintDevDebug
```

Install and run (optional)

```powershell path=null start=null
# Install devDebug on a connected device
.\gradlew.bat :app:installDevDebug

# Launch via implicit intent (example recognizable by many keyboards/apps)
# Package: org.futo.voiceinput (dev flavor adds .dev suffix to appId)
# For dev flavor, the applicationId is org.futo.voiceinput.dev
adb shell am start -a android.speech.action.RECOGNIZE_SPEECH
```

Artifacts
- APKs/AABs are under app/build/outputs/ (e.g.,
  - app/build/outputs/apk/dev/release/app-dev-release.apk
  - app/build/outputs/apk/standalone/release/app-standalone-release.apk
  - app/build/outputs/bundle/playStoreRelease/app-playStore-release.aab)
- Lint reports under app/build/reports/lint/.
- Unit test results under app/build/test-results/… and HTML reports under app/build/reports/tests/…

High-level architecture and structure

Modules and flavors
- Modules: root project with application module :app and a payment helper dependency :dep:futopay:android:app.
- Product flavors (dimension "version"): dev, devSameId, playStore, standalone, fDroid.
  - dev/devSameId: includes both Play Store and PayPal billing sources and update checking; dev uses applicationIdSuffix ".dev".
  - playStore: Play Billing only; no in-app auto-update (updates via Play Store).
  - standalone and fDroid: PayPal billing only; include in-app update flow.

UI entry points
- RecognizeActivity (org.futo.voiceinput.RecognizeActivity)
  - Handles implicit intent android.speech.action.RECOGNIZE_SPEECH.
  - Hosts a Compose UI surface (RecognizeWindow) with a shared recognition view abstraction.
- VoiceInputMethodService (org.futo.voiceinput.VoiceInputMethodService)
  - IME integration; renders a Compose UI surface (RecognizerInputMethodWindow) and injects transcribed text via currentInputConnection.
  - Schedules update/migration jobs on create.

Shared recognition view layer
- RecognizerView (abstract class) encapsulates the interaction between UI and the recognition engine:
  - Exposes setContent and multiple UI helpers (loading, partial result surface, mic error), shared across activity/service windows.
  - Handles gestures to pause/end VAD using recognizerSurfaceClickable.

Audio capture and inference orchestration
- AudioRecognizer (abstract class)
  - Manages microphone capture via AudioRecord, audio focus, and VAD (android-vad) with configurable model and frame size.
  - Buffers up to 30s (can expand if allowed), tracks magnitude state for UI, and streams partial results.
  - Loads Whisper models according to settings (English vs multilingual) and an optional forced language.
  - On OutOfMemoryError, surfaces RunState.OOMError and attempts recovery.

Model management and GGML wrapper
- WhisperModelWrapper and WhisperModel (org.futo.voiceinput.ml)
  - Loads primary and optional fallback English models (GGML), mapping files from assets or internal storage.
  - Provides onStatusUpdate (RunState) and onPartialDecode callbacks; can switch models mid-run via a BailLanguageException if language identification bails to English.
- WhisperGGML (org.futo.voiceinput.ggml)
  - Kotlin wrapper over native library voiceinput with a single-threaded inference context; exposes suspend infer(...), delivering partial results via a JNI callback.

Native layer (NDK/CMake)
- app/src/main/cpp/CMakeLists.txt builds a shared library named voiceinput linking against android and log.
  - Sources include whisper.cpp and ggml (*.c) along with JNI glue (jni_common.cpp) and voiceinput.cpp.
  - CMake min version 3.22.1; C++ flags -O3 -Wall -Wextra -g3; NEON flags for armeabi-v7a.
- The library is loaded from Kotlin in WhisperGGML via System.loadLibrary("voiceinput").

Settings, payments, updates, and crash reporting
- Settings implemented with Jetpack Compose and DataStore (org.futo.voiceinput.settings.*). Toggles control multilingual mode, language subsets, beam search, symbol suppression, 30s limit, etc.
- Payments: flavor-dependent inclusion of Play Billing or PayPal (org.futo.voiceinput.payments.*). The :dep:futopay module is wired for all flavors.
- Updates: jobs scheduled to check for updates in non-Play flavors (org.futo.voiceinput.updates).* 
- Crash reporting: ACRA (acra-http and acra-dialog) is wired and gated by crashreporting.properties; missing file disables reporting via BuildConfig flags.

Tests
- JVM unit tests (app/src/test/java): FeatureExtractorTest validates DSP utilities (linspace, Hann window, diff, mel filter bank) against expected arrays.
- Instrumented tests (app/src/androidTest/java): FeatureExtractorTestAndroid validates melSpectrogram output against reference data using app assets.
  - Requires a device/emulator; invoked via connectedDevDebugAndroidTest.

Environment and configuration notes
- Android Gradle Plugin 8.9.2; Kotlin 2.1.0; Compose compiler ext 1.4.6; compileSdk/targetSdk 35; minSdk 24.
- External native build is enabled; Gradle will invoke CMake automatically during assemble/test tasks.
- Prebuilt AARs under libs/ (vad, pocketfft, tflite) are resolved via flatDir repository (settings.gradle). These can be rebuilt following libs/README.md.
- Signing:
  - For release builds, optionally provide keystore.properties at project root with keyAlias, keyPassword, storeFile, storePassword. If absent, release uses debug signing (and logs a notice) which is unsuitable for distribution.
  - CI uses setUpPropertiesCI.sh to configure signing and runs assemble/bundle tasks for all flavors.

CI reference (authoritative task names)
- See .gitlab-ci.yml:
  - Unit tests: :app:testDevDebugUnitTest
  - Dev build artifact: :app:assembleDevRelease
  - Release artifacts: :app:bundlePlayStoreRelease, :app:assembleStandaloneRelease, :app:assembleFDroidRelease

Paths to check first
- app/build.gradle for flavors, sourceSets, dependencies, and native build config.
- app/src/main/cpp/CMakeLists.txt for native sources and build flags.
- app/src/main/java/org/futo/voiceinput for entry points and recognition pipeline.
- .gitlab-ci.yml for CI task naming and artifact paths.
- README.md for flavor descriptions and a quick build command.

