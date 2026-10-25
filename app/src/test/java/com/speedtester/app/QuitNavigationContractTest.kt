package com.speedtester.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the bottom Quit menu and its app-owned shutdown behavior. */
class QuitNavigationContractTest {
    @Test fun quitStaysBesideSettingsAndOldControlsDoNotReturn() {
        val relative = "app/src/main/java/com/speedtester/app/MainActivity.kt"
        val src = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        assertTrue("Missing label = { Text(Settings) }", src.contains("label = { Text(\"Settings\") }"))
        assertTrue("Missing label = { Text(Quit) }", src.contains("label = { Text(\"Quit\") }"))
        assertTrue("Missing scope.cancel()", src.contains("scope.cancel()"))
        assertTrue("Missing finishAndRemoveTask()", src.contains("finishAndRemoveTask()"))

        assertTrue(
            "Quit must follow Settings in the bottom menu",
            src.indexOf("label = { Text(\"Quit\") }") > src.indexOf("label = { Text(\"Settings\") }"),
        )
    }
}
