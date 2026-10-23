# Shared Clips

Android app that finds saved TikTok videos in `DCIM/Camera`, moves them out of the folder Google Photos backs up, and deletes them after a few days.

Plan: see the "TikTok Video Cleanup: Product & Technical Plan" doc in the project.

## Status

- Scans `DCIM/Camera` videos via MediaStore and scores each one (`detect/TikTokDetector.kt`).
- The user picks what cleanup does: move matches to `Movies/TikTok Downloads/` (default) or delete them to the system trash (recoverable for 30 days). The choice is remembered.
- One tap applies it to all matches (one system consent dialog per batch on Android 11+).

- Automatic mode: a WorkManager job with a MediaStore content-URI trigger moves new confirmed TikTok videos out of Camera within seconds. With "Media management" access (Android 12+) it runs silently; otherwise, or if Android blocks the background request, a notification offers a one-tap move.
- Auto-delete: a daily job trashes videos in TikTok Downloads saved more than N days ago (Never, 1, 3, 7, 14 or 30 days), with the same silent-or-notification fallback.

Not built yet: "Not sure" review, other source apps.

## Build

Open the folder in Android Studio (Ladybug or newer) and run `app`. Min SDK 29, target SDK 35.
Android Studio generates the Gradle wrapper on first sync; from the command line, run `gradle wrapper` once.

Unit tests for the detector: `./gradlew :app:testDebugUnitTest`.
