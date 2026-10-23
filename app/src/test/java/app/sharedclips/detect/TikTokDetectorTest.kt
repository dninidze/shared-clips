package app.sharedclips.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class TikTokDetectorTest {

    private fun facts(
        name: String = "a.mp4",
        owner: String? = null,
        w: Int = 1080,
        h: Int = 1920,
        dur: Long = 15_000,
        capture: Boolean? = null,
        path: String = "DCIM/Camera/",
    ) = VideoFacts(name, path, owner, w, h, dur, capture)

    @Test
    fun ownerPackageTikTokIsDecisive() {
        val d = TikTokDetector.evaluate(facts(name = "VID_20261023_101500.mp4", owner = "com.zhiliaoapp.musically"))
        assertEquals(Verdict.TIKTOK, d.verdict)
    }

    @Test
    fun cameraOwnerIsNeverTikTok() {
        val d = TikTokDetector.evaluate(facts(name = "tiktok_style.mp4", owner = "com.sec.android.app.camera"))
        assertEquals(Verdict.NOT_TIKTOK, d.verdict)
    }

    @Test
    fun cameraFilenameWithCaptureMetadataIsNotTikTok() {
        val d = TikTokDetector.evaluate(facts(name = "PXL_20261023_101500123.mp4", capture = true))
        assertEquals(Verdict.NOT_TIKTOK, d.verdict)
    }

    @Test
    fun hashNameWithoutCaptureMetadataIsTikTok() {
        val d = TikTokDetector.evaluate(facts(name = "3f9a1c0b7e2d4a6f8b1c.mp4", capture = false))
        assertEquals(Verdict.TIKTOK, d.verdict)
    }

    @Test
    fun downloaderNameIsTikTok() {
        val d = TikTokDetector.evaluate(facts(name = "SnapTik_7342.mp4"))
        assertEquals(Verdict.TIKTOK, d.verdict)
    }

    @Test
    fun ambiguousPortraitClipIsUnsure() {
        val d = TikTokDetector.evaluate(facts(name = "clip.mp4", capture = false))
        assertEquals(Verdict.UNSURE, d.verdict)
    }

    @Test
    fun landscapeCameraVideoIsNotTikTok() {
        val d = TikTokDetector.evaluate(facts(name = "20261023_101500.mp4", w = 1920, h = 1080))
        assertEquals(Verdict.NOT_TIKTOK, d.verdict)
    }
}
