package com.speedtester.app

import java.util.concurrent.TimeUnit

/** Prevent stale last-known location fixes from misrepresenting distance to test servers. */
internal object LocationFixPolicy {
    private val maxAgeNanos = TimeUnit.MINUTES.toNanos(15)

    fun isFresh(fixElapsedRealtimeNanos: Long, nowElapsedRealtimeNanos: Long): Boolean =
        fixElapsedRealtimeNanos > 0L && nowElapsedRealtimeNanos >= fixElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - fixElapsedRealtimeNanos <= maxAgeNanos
}
