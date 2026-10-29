package com.speedtester.app
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
/** Shares the existing n2k02theme header and navigation. */
@Composable
internal fun SpeedTesterLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    N2K02UnifiedLogsPage(modifier = modifier, appName = "SpeedTester", onBack = onBack)
}
