# SpeedTester

A lightweight Android network diagnostics toolkit (minSdk 29) built with Kotlin and Jetpack Compose.

## Features
- Download/upload throughput tests with separate live download and upload chart series
- Average and peak throughput estimates during each test
- TCP latency and jitter before transfer, plus loaded-latency and connection-failure probes afterward
- Wi-Fi/network status (RSSI, frequency, link speed where Android exposes them)
- Local history with shareable individual results, expanded CSV export, and configurable custom LibreSpeed-compatible endpoint
- Live connection diagnostics, server host/protocol details, and Wi-Fi signal quality information
- GitHub Releases update check and APK download/install handoff
- Manual tests only; no continuous background polling

## Build
Requires JDK 17+, Android SDK platform 35+, and Gradle 8.13+.

```sh
gradle testDebugUnitTest assembleDebug
```

Release builds are published by GitHub Actions. Configure `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` repository secrets for a signed release APK. Keep the keystore stable between releases so Android can update existing installations.

## Test servers
The default mode uses LibreSpeed-compatible endpoints configured in the app. Public third-party speed-test services do not all provide a stable public API; add a provider only when its endpoint and terms permit automated testing. Speed measurements depend on server capacity and routing.
