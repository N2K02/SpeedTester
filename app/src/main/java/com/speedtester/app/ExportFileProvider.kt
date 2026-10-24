package com.speedtester.app

import androidx.core.content.FileProvider

/**
 * Dedicated file-sharing provider for exported speed-test results. Using a
 * distinct class prevents Android's manifest merger from conflating this
 * provider with the shared updater's own FileProvider instance.
 */
class ExportFileProvider : FileProvider()
