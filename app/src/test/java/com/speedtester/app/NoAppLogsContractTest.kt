package com.speedtester.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun settingsDoNotOfferLogs() {
        assertFalse(src("app/src/main/java/com/speedtester/app/MainActivity.kt").contains("SpeedTesterLogsPage"))
    }
    @Test fun noJournal() {
        assertTrue(src("app/src/main/java/com/speedtester/app/AppLog.kt").contains("Log.d("))
        assertFalse(src("app/src/main/java/com/speedtester/app/AppLog.kt").contains("appendText("))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}
