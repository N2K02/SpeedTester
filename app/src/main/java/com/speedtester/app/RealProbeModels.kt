package com.speedtester.app

/**
 * Evidence levels intentionally distinguish measured results, failed measurements and tests that
 * could not run. A web URL returning HTTP 200 never proves a game's UDP loss or chat delivery.
 */
enum class RealProbeState { VERIFIED, DEGRADED, UNAVAILABLE, NOT_CONFIGURED }

data class RealProbeResult(
    val id: String,
    val title: String,
    val state: RealProbeState,
    val detail: String,
    val rxBytes: Long? = null,
    val txBytes: Long? = null
)

data class RealProbeConfig(
    val enabled: Boolean = false,
    val videoUrl: String = DEFAULT_VIDEO_URL,
    val udpHost: String = "",
    val udpPort: Int = 0,
    val messageUrl: String = "",
    val messageRoom: String = ""
) {
    companion object {
        // Microsoft's documented clear, 3840x2160 H.264 test vector (24 fps, ~12 Mbps).
        // Never claim 4K unless the player reports UHD dimensions and renders frames.
        const val DEFAULT_VIDEO_URL =
            "https://test.playready.microsoft.com/media/profficialsite/tearsofsteel_4k_60s_24fps.12000kbps.3840x2160.h264-8b.2ch.128kbps.aac.mp4"
    }
}

object RealProbeAnalysis {
    fun video(
        width: Int, height: Int, rendered: Boolean, playedMs: Long, droppedFrames: Int,
        rebufferMs: Long, durationMs: Long
    ): RealProbeResult {
        if (!rendered || playedMs < 6_000L) {
            return RealProbeResult("video", "4K playback", RealProbeState.UNAVAILABLE,
                "Real video playback was not established; no 4K result claimed.")
        }
        val is4k = maxOf(width, height) >= 3840 && minOf(width, height) >= 2160
        val degraded = !is4k || droppedFrames >= 10 || rebufferMs > 1_500L
        return RealProbeResult("video", "4K playback",
            if (degraded) RealProbeState.DEGRADED else RealProbeState.VERIFIED,
            "${width}×${height} decoded · ${playedMs}ms played · ${droppedFrames} dropped frames · " +
                "${rebufferMs}ms rebuffered · ${durationMs}ms elapsed" +
                if (!is4k) " · 4K was not verified" else " · actual UHD playback")
    }

    /** No valid reply means server unavailable, NOT 100% UDP packet loss. */
    fun udp(sent: Int, replies: Int, timings: List<Long>): RealProbeResult {
        if (sent <= 0 || replies <= 0 || timings.isEmpty()) {
            return RealProbeResult("game", "UDP game-action round trip", RealProbeState.UNAVAILABLE,
                "No authenticated echo reply. Endpoint may be blocked or offline; loss cannot be measured.")
        }
        val med = timings.sorted()[timings.size / 2]
        val lossPct = ((sent - replies).coerceAtLeast(0) * 100.0 / sent)
        val state = if (lossPct > 2.0 || med > 100) RealProbeState.DEGRADED else RealProbeState.VERIFIED
        return RealProbeResult("game", "UDP game-action round trip", state,
            "${replies}/${sent} matching UDP echoes · %.1f%% packet loss · median RTT ${med}ms".format(
                java.util.Locale.US, lossPct
            ) + " · controlled relay, not performance inside a third-party game")
    }

    /** Only an actual other client ACK proves delivery; server acceptance does not. */
    fun message(sent: Int, delivered: Int, rtts: List<Long>): RealProbeResult {
        if (sent <= 0 || delivered <= 0) {
            return RealProbeResult("message", "Two-device message delivery",
                RealProbeState.UNAVAILABLE,
                "No receiver acknowledgements. A connected test receiver is required; no delivery claimed.")
        }
        val med = rtts.sorted().getOrNull(rtts.size / 2)
        return RealProbeResult("message", "Two-device message delivery",
            if (delivered == sent) RealProbeState.VERIFIED else RealProbeState.DEGRADED,
            "${delivered}/${sent} acknowledged by the receiving device · " +
                (med?.let { "median end-to-end $it ms · " } ?: "") +
                "controlled test messages, not WhatsApp delivery")
    }
}
