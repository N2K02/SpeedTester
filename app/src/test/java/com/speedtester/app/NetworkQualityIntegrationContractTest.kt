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
        assertTrue(main.contains("selected = activeTab == 10"))
        assertTrue(main.contains("label = { Text(\"Quality\") }"))
        assertTrue(main.contains("onClick = { activeTab = 10; showUpdateDetails = false }"))
        assertFalse(main.substringAfter("3 -> if (!showUpdateDetails) LazyColumn(")
            .contains("headlineContent = { Text(\"Network Quality\") }"))
        assertTrue(main.contains("SpeedTestHistoryTabbedScreen("))
        assertTrue(main.contains("SpeedTestHistoryTabbedScreen("))
        assertFalse(main.contains("networkQualityOrigin"))
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
        assertTrue(page.contains("NetworkQualityHistory.save(context, newReport)"))
        assertFalse(page.contains("onViewHistory"))
        assertFalse(page.contains("HorizontalPager("))
        assertTrue(page.contains("QualityNetworkPathCard(path = path"))
        assertTrue(page.contains("Canvas(Modifier.fillMaxWidth().height(40.dp))"))
        assertTrue(page.contains("QualityPill("))
        assertEquals("Only one primary Run/Cancel button may be rendered", 1,
            Regex("(?m)^\\s*Button\\(").findAll(page).count())
        assertTrue("Primary button switches from Run to Cancel",
            page.contains("if (running) cancelTest() else startTest()"))
        val tabs = source("NetworkQualityHistoryTabs.kt")
        assertTrue(tabs.contains("HorizontalPager(state = pagerState"))
        assertTrue(tabs.contains("pagerState.animateScrollToPage(page)"))
        assertTrue(tabs.contains("SpeedTestHistoryScreen("))
        assertTrue(tabs.contains("NetworkQualityHistoryPage("))
        assertTrue(page.contains("network_quality_profile"))
        assertTrue(page.contains("Test History → Network Quality."))
        assertTrue(runner.contains(".eventListenerFactory(object : EventListener.Factory"))
        assertTrue(page.contains("if (!running)"))
        val path = source("NetworkQualityPath.kt")
        assertTrue(path.contains("QualityPathState.UNMEASURED"))
        assertTrue(path.contains("QualityPathState.FAILED"))
        assertTrue(path.contains("QualityPathState.TESTING"))
        assertTrue(path.contains("QualityPathState.SUCCESS"))
        assertTrue(page.contains("QualityNetworkPathCard(path = path"))
        assertTrue(page.contains("QualityScenarioRow(scenario)"))
        assertTrue(page.contains("runCatching { NetworkQualityRunner.access(context) }"))
        assertTrue(runner.contains("TrafficStats.getUidRxBytes"))
        assertTrue(runner.contains("RealVideoProbe.run("))
        assertTrue(runner.contains("RealNetworkProbes.gateway("))
        assertTrue(runner.contains("RealNetworkProbes.route("))
        assertTrue(runner.contains("RealNetworkProbes.udp("))
        assertTrue(runner.contains("RealNetworkProbes.messageRelay("))
        assertTrue(page.contains("PlayerView(context)"))
        assertTrue(page.contains("quality_udp_host"))
        assertTrue(page.contains("quality_video_url"))
        assertTrue(page.contains("receiver.mjs"))
        assertTrue(runner.contains("onProgress(title, completed, total)"))
        assertTrue(page.contains("network_quality_real_enabled"))
        assertFalse(page.contains("quality_message_room"))
        assertTrue(source("RealVideoProbe.kt").contains("MAX_VIDEO_BYTES = 24L * 1024 * 1024"))
        assertTrue(source("RealProbeModels.kt").contains("No authenticated echo reply"))
    }
}
