# Shared Clips

Android app that finds saved TikTok videos in `DCIM/Camera`, moves them out of the folder Google Photos backs up, and deletes them after a few days.

Plan: see the "TikTok Video Cleanup: Product & Technical Plan" doc in the project.

## Status

- Scans `DCIM/Camera` videos via MediaStore and scores each one (`detect/TikTokDetector.kt`).
- The user picks what cleanup does: move matches to `Movies/TikTok Downloads/` (default) or delete them to the system trash (recoverable for 30 days). The choice is remembered.
- One tap applies it to all matches. With "Media management" (Android 12+) and media location access there is no system confirmation; otherwise Android asks once per batch.

- Automatic mode: a WorkManager job with a MediaStore content-URI trigger looks at new confirmed TikTok videos within seconds of a change.
  - With "All files access" granted, it moves them out of Camera itself, with no tap. This is the only way Android lets a background job move another app's files. It is fine for a sideloaded APK, but Google Play does not allow it for this kind of app, so a Play build would drop it.
  - Without it, a notification appears and one tap moves them (no popup when Media management is allowed).
  - TikTok only looks for its earlier copy in Camera, so saving or sharing a video again after it was moved writes a new copy there. That copy is moved too (as "name (1).mp4") and logged as "saved again".
- Auto-delete: a daily job trashes videos in TikTok Downloads saved more than N days ago (Never, 1, 3, 7, 14 or 30 days): directly with All files access, otherwise through a one-tap notification.

- Videos are only moved or deleted once they have finished saving: the live file is non-empty and has not been written for 15 s. This guards against moving a file another app is still writing. An entry still empty after 10 minutes is a failed save: it is never moved, and "Delete all" can remove it.
- Each notification carries the videos it announced; tapping it acts on exactly those. It alerts once per video. Swiping it away keeps it away for those videos; if Android removes it instead (a restart or an app update), it comes back without sound while they are still in Camera.
- Background reliability: Android delays jobs of apps that are rarely opened and pauses apps unused for months. The home screen offers "Allow background use" (unrestricted battery) and "Keep running when unused". On OnePlus, OPPO, Realme, Xiaomi and similar phones, also allow auto launch and background activity in the app's battery settings, or automatic mode pauses after a restart until the app is opened.
- Activity log on the home screen records what the app saw and did, with a Share button for bug reports.

Not built yet: "Not sure" review, other source apps.

## Build

Open the folder in Android Studio (Ladybug or newer) and run `app`. Min SDK 30 (Android 11; moving another
app's videos needs MediaStore write requests or All files access, which Android 10 lacks), target SDK 35.
Android Studio generates the Gradle wrapper on first sync; from the command line, run `gradle wrapper` once.
Debug builds are signed with the checked-in `app/debug.keystore`, so every CI APK installs over the previous one.

## Tests

- Unit tests (detector, save-finished and failed-save rules): `gradle :app:testDebugUnitTest`.
- Device tests run on Android emulators (API 31 and 34) in GitHub Actions on every push. They install
  `:faketiktok`, a test-only app with TikTok's package name (`com.zhiliaoapp.musically`), which saves a
  sample video into DCIM/Camera in several ways (TikTok's own save sequence, including an empty entry that
  waits before the copy starts, MediaStore, slow stream, IS_PENDING, a raw path reopened per chunk while the
  gallery already lists it, and saving the same video again after it was moved). The tests
  check each save ends up as complete files in TikTok Downloads only, with matching gallery entries, that two
  copies never exist at once, and that tapping the posted notification needs no confirmation popup. They also
  check that a dismissed notification is not posted again after an unrelated video change while one Android
  removed comes back, and that the
  auto-delete job's notification moves expired videos to the trash (`AutoDeleteTest`).
  `AllFilesAccessTest` runs in a second pass with "All files access" granted and checks nothing needs a tap,
  including auto-delete.
  To run them locally: start an emulator, `adb install faketiktok/build/outputs/apk/debug/faketiktok-debug.apk`
  (after `gradle :faketiktok:assembleDebug`), then
  `gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=app.sharedclips.AllFilesAccessTest`
  and `gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.sharedclips.AllFilesAccessTest`.
