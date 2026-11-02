# PocketCam

Android network-camera prototype following the baseline in [`docs/requirements.md`](docs/requirements.md).

## Current implementation

- Java application with `minSdk 17`, `targetSdk 25`, and `compileSdk 36` (no `maxSdkVersion`).
- Legacy Camera API capture and device-supported resolution selection. The default request is 320×240; unsupported sizes fall back to the smallest supported preview size.
- HTTP/MJPEG service on TCP port 8080. Both `/` and `/stream.mjpg` require HTTP Basic credentials configured in the app.
- Runtime CAMERA permission request on API 23 and later; API 17–22 use install-time permission grants.
- Service start/stop status and startup error state are persisted for UI reporting. Optional boot start defaults off and only starts the local camera service.
- frpc simple fields and complete TOML text can be saved locally. The frpc Android core, file import, validation, lifecycle controls, and connection status are not implemented yet.

## Build

Use the Android SDK with platforms 22 and 36, JDK 17 or newer, and the Gradle wrapper. Run `gradlew.bat assembleDebug` on Windows or `./gradlew assembleDebug` on Unix-like systems.

## Current verification

- Built and installed on a vivo Y51t L, Android 5.1.1 / API 22 / `armeabi-v7a`.
- On that device, `/` and `/stream.mjpg` returned HTTP 401 for missing and wrong credentials, and HTTP 200 with configured credentials.
- API 17, API 23 permission flows, API 25, boot recovery, and long-duration stability remain unverified.
