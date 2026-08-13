---
name: android-app-optimization
description: >-
  Enables and troubleshoots Android R8 app optimization (minify, resource
  shrinking, keep rules, mapping). Use when enabling app optimization, R8,
  ProGuard, isMinifyEnabled, shrinkResources, reducing APK/AAB size, or fixing
  release-only crashes after minification.
---

# Android app optimization (R8)

Follow Google’s guidance: enable optimization on **release** (not day-to-day debug). Always use `proguard-android-optimize.txt` — never legacy `proguard-android.txt`.

## Enable (legacy DSL — AGP &lt; 9.3)

In the app module `build.gradle.kts` release build type:

```kotlin
isMinifyEnabled = true
isShrinkResources = true
proguardFiles(
    getDefaultProguardFile("proguard-android-optimize.txt"),
    "proguard-rules.pro"
)
```

Both minify and shrinkResources should be on together.

## Enable (AGP 9.3+)

```kotlin
optimization {
    enable = true
}
```

Default optimize keep file is included unless omitted.

## gradle.properties

- **AGP 8.6 – &lt; 9.0:** add `android.r8.optimizedResourceShrinking=true`
- **AGP 9.0+:** optimized resource shrinking is on when shrinkResources is enabled — no flag needed
- **Never** set `android.enableR8.fullMode=false` (full mode is default on AGP 8+)
- Play Console “App optimization” score expects AGP **9.0+**, resource shrinking, and package renaming (`-repackageclasses`). Prefer `-repackageclasses 'o'` (not empty package) if Room/WorkManager (e.g. via Unity Ads) crash on startup.

## Keep rules checklist

Add app rules in `proguard-rules.pro` (or AGP 9.3+ `src/*/keepRules/*.keep`) when:

- JNI / native libs (WebRTC, etc.)
- Reflection / SPI / MQTT callbacks
- Crash retrace: keep SourceFile + LineNumberTable

Prefer library consumer ProGuard files for Compose, AndroidX, security-crypto. Add app keeps only if `:app:assembleRelease` fails or release crashes.

## Verify

1. `./gradlew :app:assembleRelease` (or `gradlew.bat` on Windows)
2. Fix R8 missing-class / keep errors iteratively
3. Confirm `app/build/outputs/mapping/release/mapping.txt` exists for retrace
4. Smoke-test release APK/AAB on a device (pairing, transfer, crypto paths)

## References

- https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- https://developer.android.com/agents/skills/performance/r8-analyzer/references/CONFIGURATION
