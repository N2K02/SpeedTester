package com.speedtester.app

import android.content.Context
import android.net.Network
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A real, muted, visibly rendered video decode; the media MUST report >= 3840x2160. */
@UnstableApi
object RealVideoProbe {
    private const val MAX_VIDEO_BYTES = 24L * 1024 * 1024
    private const val PLAYBACK_TARGET_MS = 7_000L
    private const val MAX_ELAPSED_MS = 15_000L

    /** Shared byte budget across every MP4 range request; no unbounded cellular download. */
    private class BoundedSource(
        private val delegate: DataSource, private val loaded: AtomicLong
    ) : DataSource {
        override fun addTransferListener(listener: TransferListener) =
            delegate.addTransferListener(listener)
        override fun open(dataSpec: DataSpec): Long = delegate.open(dataSpec)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val budget = MAX_VIDEO_BYTES - loaded.get()
            if (budget <= 0) throw IOException("Video test data cap reached")
            val count = delegate.read(buffer, offset, minOf(length, budget.toInt()))
            if (count > 0) loaded.addAndGet(count.toLong())
            return count
        }
        override fun getUri(): android.net.Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() = delegate.close()
    }

    suspend fun run(
        context: Context,
        network: Network,
        url: String,
        preview: PlayerView?
    ): RealProbeResult = withContext(Dispatchers.Main) {
        if (preview == null || !url.startsWith("https://")) {
            return@withContext RealProbeResult("video", "4K playback",
                RealProbeState.NOT_CONFIGURED, "A visible player and HTTPS UHD video are required.")
        }
        val bytes = AtomicLong(0)
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val factory = OkHttpDataSource.Factory(client)
        val budgetFactory = DataSource.Factory {
            BoundedSource(factory.createDataSource(), bytes)
        }
        val player = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                1_500, 3_500, 700, 1_000
            ).build())
            .build()
        var width = 0
        var height = 0
        var droppedFrames = 0
        var rendered = false
        var rebufferSince = 0L
        var rebufferMs = 0L
        var playerError: String? = null
        val start = SystemClock.elapsedRealtime()
        val analytics = object : AnalyticsListener {
            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime, videoSize: VideoSize
            ) {
                width = videoSize.width
                height = videoSize.height
            }
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime, droppedFramesCount: Int, elapsedMs: Long
            ) {
                droppedFrames += droppedFramesCount
            }
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long
            ) {
                rendered = true
            }
        }
        player.addAnalyticsListener(analytics)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING && rendered && rebufferSince == 0L) {
                    rebufferSince = SystemClock.elapsedRealtime()
                } else if (state == Player.STATE_READY && rebufferSince != 0L) {
                    rebufferMs += SystemClock.elapsedRealtime() - rebufferSince
                    rebufferSince = 0L
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = error.errorCodeName
            }
        })
        try {
            preview.useController = false
            preview.player = player
            player.volume = 0f
            player.setMediaSource(ProgressiveMediaSource.Factory(budgetFactory)
                .createMediaSource(MediaItem.fromUri(url)))
            player.prepare()
            player.play()
            withTimeoutOrNull(MAX_ELAPSED_MS) {
                while (playerError == null &&
                    (!rendered || player.currentPosition < PLAYBACK_TARGET_MS)) {
                    currentCoroutineContext().ensureActive()
                    delay(200)
                }
            }
            if (rebufferSince != 0L) rebufferMs += SystemClock.elapsedRealtime() - rebufferSince
            val elapsed = SystemClock.elapsedRealtime() - start
            val measured = RealProbeAnalysis.video(width, height, rendered,
                player.currentPosition.coerceAtLeast(0), droppedFrames, rebufferMs, elapsed)
            measured.copy(
                detail = measured.detail + " · media bytes ${bytes.get()}/$MAX_VIDEO_BYTES" +
                    (playerError?.let { " · player error $it" } ?: "")
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            RealProbeResult("video", "4K playback", RealProbeState.UNAVAILABLE,
                "Video decoder or media transport failed (${e.javaClass.simpleName}).")
        } finally {
            preview.player = null
            player.release()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
