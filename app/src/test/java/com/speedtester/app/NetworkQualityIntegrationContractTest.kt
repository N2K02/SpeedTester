package com.speedtester.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect the UI integration and the no-background-work resource budget. */
class NetworkQualityIntegrationContractTest {
    private fun source(name: String): String {
        val relative = "com/speedtester/app/$name"
        return listOf(File("app/src/main/java", relative), File("src/main/java", relative))
            .firstOrNull { it.isFile }?.readText() ?: error("Source missing: $relative")
    }

    @Test fun homeLinksToDedicatedPageAndSingleSharedHeader() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("10 -> NetworkQualityPage("))
        assertTrue(main.contains("activeTab == 10 -> \"Network Quality\""))
        assertTrue(main.contains("BackHandler(enabled = activeTab == 10)"))
        assertTrue(main.contains("activeTab = networkQualityOrigin"))
        assertTrue(main.contains("networkQualityOrigin = 0; activeTab = 10"))
        assertTrue(main.contains("networkQualityOrigin = 3"))
        assertTrue(main.contains("Card(onClick = { networkQualityOrigin = 0; activeTab = 10 }"))
        val page = source("NetworkQualityPage.kt")
        assertFalse(page.contains("TopAppBar("))
        assertTrue(page.contains("N2K02Theme").not()) // central theme owns page styling
    }

    @Test fun probesHaveHardTimeoutsAndCancelWithPage() {
        val runner = source("NetworkQualityRunner.kt")
        val page = source("NetworkQualityPage.kt")
        assertTrue(runner.contains(".callTimeout(2800, TimeUnit.MILLISECONDS)"))
        assertTrue(runner.contains("it.connect(InetSocketAddress(\"1.1.1.1\", 443), 1200)"))
        assertTrue(runner.contains("coroutineContext.ensureActive()"))
        assertTrue(runner.contains("suspendCancellableCoroutine"))
        assertTrue(runner.contains("continuation.invokeOnCancellation"))
        assertTrue(runner.contains("runCatching { socket.close() }"))
        assertTrue(runner.contains("call.cancel()"))
        assertTrue(runner.contains("registerDefaultNetworkCallback(callback)"))
        assertTrue(runner.contains("cm.unregisterNetworkCallback(callback)"))
        assertTrue(runner.contains("checkNetwork()"))
        assertTrue(runner.contains("network.socketFactory"))
        assertTrue(runner.contains("connectionPool.evictAll()"))
        assertTrue(page.contains("DisposableEffect(context)"))
        assertTrue(page.contains("connectivity.registerDefaultNetworkCallback(callback)"))
        assertTrue(page.contains("connectivity.unregisterNetworkCallback(callback)"))
        assertTrue(page.contains("job?.cancel()"))
        assertTrue(page.contains("failure = \"Network changed. Run a new test.\""))
        assertFalse(runner.contains("GlobalScope"))
        assertFalse(runner.contains("while (true)"))
        assertFalse(page.contains("LaunchedEffect(Unit)"))
    }

    @Test fun serviceCardsDoNotMisrepresentApplicationPerformance() {
        val page = source("NetworkQualityPage.kt")
        assertTrue(page.contains("not actual video playback, gameplay or message delivery"))
        assertTrue(page.contains("TCP connection failures are not ICMP/UDP packet loss"))
        val runner = source("NetworkQualityRunner.kt")
        assertTrue(runner.contains("Request.Builder().url(url).get()"))
        assertTrue(runner.contains("response.use"))
        assertTrue(page.contains("report = null"))
        assertTrue(page.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(page.contains("lifecycleOwner.lifecycle.removeObserver(observer)"))
        assertTrue(page.contains("NetworkQualityHistory.clear(context)"))
        assertTrue(page.contains("network_quality_profile"))
        assertTrue(runner.contains(".eventListenerFactory(object : EventListener.Factory"))
        assertTrue(page.contains("if (!running)"))
        assertTrue(page.contains("QualityPathStep(\"ISP\", null"))
        assertTrue(page.contains("QualityPathStep(\"Gateway\", null"))
        assertTrue(page.contains("finding.informational -> Icons.Default.Info"))
        assertTrue(page.contains("runCatching { NetworkQualityRunner.access(context) }"))
        assertTrue(runner.contains("TrafficStats.getUidRxBytes"))
    }
}
