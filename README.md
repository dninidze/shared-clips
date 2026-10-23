# Shared Clips

Android app that finds saved TikTok videos in `DCIM/Camera`, moves them out of the folder Google Photos backs up, and deletes them after a few days.

Plan: see the "TikTok Video Cleanup: Product & Technical Plan" doc in the project.

## Status (MVP, manual mode)

- Scans `DCIM/Camera` videos via MediaStore and scores each one (`detect/TikTokDetector.kt`).
- One tap moves matches to `Movies/Shared Clips/` (one system consent dialog per batch on Android 11+).
- One tap sends matches to the system trash (recoverable for 30 days).

Not built yet: background watcher, auto-expiry after N days, "Not sure" review, settings.

## Build

Open the folder in Android Studio (Ladybug or newer) and run `app`. Min SDK 29, target SDK 35.
Android Studio generates the Gradle wrapper on first sync; from the command line, run `gradle wrapper` once.

Unit tests for the detector: `./gradlew :app:testDebugUnitTest`.
