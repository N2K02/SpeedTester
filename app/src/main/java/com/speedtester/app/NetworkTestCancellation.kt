package com.speedtester.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels the measurement promptly, including a network change before its job is attached. */
internal class NetworkTestCancellation {
    private val changed = AtomicBoolean(false)
    private val lock = Any()
    private var job: Job? = null

    fun attach(job: Job) {
        synchronized(lock) {
            this.job = job
            if (changed.get()) job.cancel(CancellationException("Network changed during test"))
        }
    }

    fun networkChanged() {
        changed.set(true)
        synchronized(lock) {
            job?.cancel(CancellationException("Network changed during test"))
        }
    }

    fun hasChanged(): Boolean = changed.get()
}
