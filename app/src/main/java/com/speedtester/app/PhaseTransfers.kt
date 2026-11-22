package com.speedtester.app

/** A request either registers before the phase stops or is cancelled before it can run. */
internal class PhaseTransfers {
    private val lock = Any()
    private val active = mutableSetOf<() -> Unit>()

    @Volatile
    var stopped: Boolean = false
        private set

    fun run(cancel: () -> Unit, action: () -> Unit) {
        val registered = synchronized(lock) { !stopped && active.add(cancel) }
        if (!registered) {
            cancel()
            return
        }
        try {
            action()
        } finally {
            synchronized(lock) { active.remove(cancel) }
        }
    }

    fun stop() {
        val cancellations = synchronized(lock) {
            stopped = true
            active.toList().also { active.clear() }
        }
        cancellations.forEach { it() }
    }
}
