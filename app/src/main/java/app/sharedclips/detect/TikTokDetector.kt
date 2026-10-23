package app.sharedclips.detect

/** Everything the detector needs about one video. Pure data so it can be unit-tested on the JVM. */
data class VideoFacts(
    val displayName: String,
    val relativePath: String?,
    val ownerPackage: String?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** True if the container carries capture-only tags (GPS, capture frame rate). Null if not read. */
    val hasCaptureMetadata: Boolean? = null,
)

enum class Verdict { TIKTOK, UNSURE, NOT_TIKTOK }

data class Detection(val verdict: Verdict, val score: Int, val reasons: List<String>)

/**
 * Scores a video on how likely it is a saved TikTok rather than something the user filmed.
 * The owner package (set by MediaStore on Android 10+) is decisive; other signals cover files
 * where it is missing.
 */
object TikTokDetector {

    val TIKTOK_PACKAGES = setOf(
        "com.zhiliaoapp.musically",    // TikTok (global)
        "com.ss.android.ugc.trill",    // TikTok (some regions)
        "com.zhiliaoapp.musically.go", // TikTok Lite
        "com.ss.android.ugc.tiktok.lite",
    )

    val CAMERA_PACKAGES = setOf(
        "com.google.android.GoogleCamera",
        "com.sec.android.app.camera",
        "com.android.camera",
        "com.android.camera2",
        "com.oneplus.camera",
        "com.oplus.camera",
        "com.motorola.camera3",
        "com.huawei.camera",
    )

    // VID_20261023_101500.mp4, PXL_20261023_101500123.mp4, 20261023_101500.mp4 (Samsung)
    private val CAMERA_NAME = Regex("""^(VID_|PXL_|IMG_)?\d{8}_\d{6,9}.*\.(mp4|3gp|mov)$""", RegexOption.IGNORE_CASE)
    private val TIKTOK_NAME_HINT = Regex("""tiktok|musically|snaptik|ssstik|tikmate""", RegexOption.IGNORE_CASE)
    // Long hex or alphanumeric hashes, as most downloaders name files
    private val HASH_NAME = Regex("""^[0-9a-f]{16,}\.mp4$|^v[0-9a-z]{20,}\.mp4$""", RegexOption.IGNORE_CASE)

    const val MATCH_THRESHOLD = 60
    const val UNSURE_THRESHOLD = 30

    fun evaluate(v: VideoFacts): Detection {
        val reasons = mutableListOf<String>()
        v.ownerPackage?.let { owner ->
            if (owner in TIKTOK_PACKAGES) return Detection(Verdict.TIKTOK, 100, listOf("Saved by TikTok ($owner)"))
            if (owner in CAMERA_PACKAGES) return Detection(Verdict.NOT_TIKTOK, 0, listOf("Recorded by camera ($owner)"))
        }

        var score = 0
        if (TIKTOK_NAME_HINT.containsMatchIn(v.displayName)) {
            score += 50; reasons += "Filename mentions TikTok or a TikTok downloader"
        }
        if (CAMERA_NAME.matches(v.displayName)) {
            score -= 40; reasons += "Camera-style filename"
        } else if (HASH_NAME.matches(v.displayName)) {
            score += 25; reasons += "Hash-style filename"
        }
        when (v.hasCaptureMetadata) {
            true -> { score -= 40; reasons += "Has camera capture metadata" }
            false -> { score += 25; reasons += "No camera capture metadata" }
            null -> Unit
        }
        if (isTikTokShape(v)) {
            score += 10; reasons += "Portrait 9:16, under 10 minutes"
        }
        if (v.relativePath?.contains("tiktok", ignoreCase = true) == true) {
            score += 30; reasons += "Stored in a TikTok folder"
        }

        val clamped = score.coerceIn(0, 100)
        val verdict = when {
            clamped >= MATCH_THRESHOLD -> Verdict.TIKTOK
            clamped >= UNSURE_THRESHOLD -> Verdict.UNSURE
            else -> Verdict.NOT_TIKTOK
        }
        return Detection(verdict, clamped, reasons)
    }

    private fun isTikTokShape(v: VideoFacts): Boolean {
        if (v.width <= 0 || v.height <= 0) return false
        val ratio = v.height.toDouble() / v.width
        return ratio in 1.70..1.82 && v.durationMs in 1..10 * 60_000L
    }
}
