# Shared Clips

Android app that finds saved TikTok videos in `DCIM/Camera`, moves them out of the folder Google Photos backs up, and deletes them after a few days.

Plan: see the "TikTok Video Cleanup: Product & Technical Plan" doc in the project.

## Status

- Scans `DCIM/Camera` videos via MediaStore and scores each one (`detect/TikTokDetector.kt`).
- The user picks what cleanup does: move matches to `Movies/TikTok Downloads/` (default) or delete them to the system trash (recoverable for 30 days). The choice is remembered.
- One tap applies it to all matches (one system consent dialog per batch on Android 11+).

- Automatic mode: a WorkManager job with a MediaStore content-URI trigger moves new confirmed TikTok videos out of Camera within seconds. With "Media management" access (Android 12+) it runs silently; otherwise, or if Android blocks the background request, a notification offers a one-tap move.
- Auto-delete: a daily job trashes videos in TikTok Downloads saved more than N days ago (Never, 1, 3, 7, 14 or 30 days), with the same silent-or-notification fallback.

- Videos are only moved or deleted once they have finished saving (no write for 15 s, checked on the live file). Moving a video while TikTok is still writing it can leave a second copy in Camera.
- Activity log on the home screen records what the app saw and did, with a Share button for bug reports.

Not built yet: "Not sure" review, other source apps.

## Build

Open the folder in Android Studio (Ladybug or newer) and run `app`. Min SDK 29, target SDK 35.
Android Studio generates the Gradle wrapper on first sync; from the command line, run `gradle wrapper` once.

## Tests

- Unit tests (detector, save-finished rule): `gradle :app:testDebugUnitTest`.
- Device tests run on Android emulators (API 31 and 34) in GitHub Actions on every push. They install
  `:faketiktok`, a test-only app with TikTok's package name (`com.zhiliaoapp.musically`), which saves a
  sample video into DCIM/Camera in several ways (MediaStore, slow stream, IS_PENDING, raw path reopened per
  chunk). The tests check the video ends up as exactly one complete file in TikTok Downloads, with one
  gallery entry, and that two copies never exist at once.
  To run them locally: start an emulator, `adb install faketiktok/build/outputs/apk/debug/faketiktok-debug.apk`
  (after `gradle :faketiktok:assembleDebug`), then `gradle :app:connectedDebugAndroidTest`.
