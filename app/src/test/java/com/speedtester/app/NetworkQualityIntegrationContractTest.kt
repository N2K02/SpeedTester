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
        assertTrue(main.contains("Card(onClick = { activeTab = 10 }"))
        val page = source("NetworkQualityPage.kt")
        assertFalse(page.contains("TopAppBar("))
        assertTrue(page.contains("N2K02Theme").not()) // central theme owns page styling
    }

    @Test fun probesHaveHardTimeoutsAndCancelWithPage() {
        val runner = source("NetworkQualityRunner.kt")
        val page = source("NetworkQualityPage.kt")
        assertTrue(runner.contains(".callTimeout(2800, TimeUnit.MILLISECONDS)"))
        assertTrue(runner.contains("socket.connect(InetSocketAddress(\"1.1.1.1\", 443), 1200)"))
        assertTrue(runner.contains("coroutineContext.ensureActive()"))
        assertTrue(runner.contains("checkNetwork()"))
        assertTrue(runner.contains("network.socketFactory"))
        assertTrue(runner.contains("connectionPool.evictAll()"))
        assertTrue(page.contains("DisposableEffect(Unit) { onDispose { job?.cancel() } }"))
        assertFalse(runner.contains("GlobalScope"))
        assertFalse(runner.contains("while (true)"))
        assertFalse(page.contains("LaunchedEffect(Unit)"))
    }

    @Test fun serviceCardsDoNotMisrepresentApplicationPerformance() {
        val page = source("NetworkQualityPage.kt")
        assertTrue(page.contains("not actual video playback, gameplay or message delivery"))
        assertTrue(page.contains("TCP connection failures are not ICMP/UDP packet loss"))
        val runner = source("NetworkQualityRunner.kt")
        assertTrue(runner.contains("Request.Builder().url(url).head()"))
        assertTrue(runner.contains("TrafficStats.getUidRxBytes"))
    }
}
