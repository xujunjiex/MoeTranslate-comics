# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MoeTranslate (萌译) is an Android screenshot translation app targeting Android 11+ (API 29+). It uses Accessibility Service for screen capture and ML Kit for local OCR, then routes recognized text through various translation APIs.

## Build

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

Requires: JDK 17, Android SDK (compileSdk 35), NDK 25.2.9519653, CMake 3.22.1.

## Architecture

**Package structure** (`app/src/main/java/com/moe/moetranslator/`):

- `translate/` — Core translation engine: `FloatingBallService` (main service, ~840 lines), `ScreenShotAccessibilityService` (screenshot capture), `OCRTextRecognizer` (ML Kit OCR), `TranslationTextAPI`/`TranslationPicAPI` (translation interfaces), `AccessibilityServiceManager` (singleton service reference)
- `manga/` — **Extension module** (added, not part of original project): manga speech bubble translation with vertical text rendering
- `game/` — **Extension module**: game dialog box translation with subtitle/overlay modes
- `bridge/` — **Extension module**: bridge layer calling original project APIs (`OCRBridge`, `TranslateBridge`, `ScreenshotBridge`)
- `me/` — Settings and API configuration UI fragments
- `geminiapi/` — Gemini AI chat feature
- `madoka/` — Live2D viewer feature
- `launch/` — First launch / onboarding
- `utils/` — Shared utilities, `Constants` enum definitions

**Translation API implementations** (`app/src/main/java/translationapi/`):
Each subdirectory implements `TranslationTextAPI` interface: `openaitranslation/`, `bingtranslation/`, `mlkittranslation/`, `nllbtranslation/`, `niutrans/`, `volctranslation/`, `deepltranslation/`, `baidutranslation/`, `tencentcloud/`, `azuretranslation/`, `customtranslation/`

**Key interfaces:**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — text translation
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — image translation
- `OCRTextRecognizer.getPicText(language, bitmap, mergeMode)` — returns plain text only (no position info)

**Screenshot flow:** `ScreenShotAccessibilityService` → `ScreenshotManager.screenshotFlow` (SharedFlow) → `FloatingBallService` collects and processes

**Config storage:** `CustomPreference` singleton wrapping `SharedPreferences` (default prefs). API keys stored encrypted via `KeystoreManager`.

**UI:** Traditional Android Views + ViewBinding (NOT Jetpack Compose). Navigation via Navigation Component fragments.

**Build variants:** Single module `:app` + `:framework` (Live2D SDK). Native code via CMake in `app/src/main/cpp/`.

## Extension Modules (manga/, game/, bridge/)

Added as independent modules — zero modification to original project files. Bridge layer pattern:
- `ScreenshotBridge` wraps `ScreenshotManager.screenshotFlow` + `AccessibilityServiceManager.takeScreenshot()`
- `OCRBridge` calls ML Kit directly for position-aware OCR (original `OCRTextRecognizer` returns plain text only)
- `TranslateBridge` reads config from `CustomPreference` and instantiates the appropriate `TranslationTextAPI`

Services: `MangaFloatingService`, `GameFloatingService` — independent foreground services with own floating balls.

## Key Constraints

- **minSdk 29** (Android 10+), **targetSdk 35**
- **arm64-v8a only** — no 32-bit support
- Accessibility Service required for screenshot capture (not MediaProjection)
- `FloatingBallService` uses `foregroundServiceType="mediaProjection"`
- License: LGPL (original project)
