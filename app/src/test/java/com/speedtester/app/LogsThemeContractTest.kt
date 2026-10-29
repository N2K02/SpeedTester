package com.speedtester.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class LogsThemeContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $p")
    @Test fun inAppLogsUseSharedThemeAndMainHeader() {
        val ui = src("app/src/main/java/com/speedtester/app/MainActivity.kt")
        val logs = src("app/src/main/java/com/speedtester/app/LogsActivity.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(ui.contains("SpeedTesterLogsPage("))
        assertFalse(ui.contains("LogsActivity::class.java"))
        assertFalse(logs.contains("class LogsActivity"))
        assertTrue(src("app/src/main/java/com/speedtester/app/UnifiedLogsPage.kt").contains("maxHeight < 430.dp"))
        assertTrue(src("app/src/main/java/com/speedtester/app/UnifiedLogsPage.kt").contains("Switch(checked = enabled"))
        assertTrue(src("app/src/main/java/com/speedtester/app/UnifiedLogsPage.kt").contains("AppLog.clear()"))
        assertTrue(src("app/src/main/java/com/speedtester/app/UnifiedLogsPage.kt").contains("onClick = onBack"))
        assertFalse(manifest.contains("com.speedtester.app.LogsActivity"))
    }
}
