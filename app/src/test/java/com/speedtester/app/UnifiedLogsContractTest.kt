package com.speedtester.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class UnifiedLogsContractTest {
    @Test fun diagnosticsArePersistentAndOnlyOneViewerExists() {
        fun source(name: String): String {
            val p = "app/src/main/java/com/speedtester/app/$name"
            return listOf(File(p), File("../$p")).firstOrNull { it.isFile }?.readText() ?: error(p)
        }
        val ui = source("MainActivity.kt")
        val viewer = source("UnifiedLogsPage.kt")
        assertTrue(ui.contains("AppLog.add(\"SPEED\", message)"))
        assertTrue(ui.contains("AppLog.init(applicationContext)"))
        assertFalse(ui.contains("diagnosticLogExpanded"))
        assertFalse(ui.contains("Text(\"Detailed diagnostic log\""))
        assertTrue(viewer.contains("Text(\"Prev\","))
        assertTrue(viewer.contains("Text(\"Next\","))
        assertTrue(viewer.contains("Text(\"Copy\","))
        assertTrue(viewer.contains("Text(\"Clear all\","))
        assertTrue(viewer.contains("Switch(checked = enabled"))
        assertTrue(viewer.contains("followLatest"))
        assertTrue(viewer.contains("Icons.Filled.ContentCopy"))
        assertTrue(viewer.contains("Icons.Filled.DeleteSweep"))
        assertTrue(viewer.contains("Icons.AutoMirrored.Filled.KeyboardArrowLeft"))
        assertTrue(viewer.contains("Icons.AutoMirrored.Filled.KeyboardArrowRight"))
        assertTrue(viewer.contains("horizontalArrangement = Arrangement.SpaceBetween"))
        assertFalse(viewer.contains("Text(\"Back\")"))
    }
    @Test fun olderAndLiveLogsAreInterleavedByTimestamp() {
        val saved = listOf("2026-10-28 23:59:58.000 [UI] old",
            "2026-10-29 10:00:02.001 [LOG] later")
        val activity = listOf("[activity] 10:00:01 event")
        assertEquals(listOf(saved[0], activity[0], saved[1]),
            LogEventOrdering.merge(saved, activity, "2026-10-29"))
    }

    @Test fun activityLogsCoverStartStopAndFailures() {
        val path = "app/src/main/java/com/speedtester/app/MainActivity.kt"
        val activity = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(activity.contains("SPEED_TEST_CANCELLED_BY_QUIT"))
        assertTrue(activity.contains("SPEED TEST SUCCESS"))
        assertTrue(activity.contains("SPEED TEST START"))
    }

}
