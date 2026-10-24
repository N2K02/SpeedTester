package com.speedtester.app

import androidx.core.content.FileProvider

/**
 * Preserve the legacy <applicationId>.updates FileProvider independently of the shared
 * updater's <applicationId>.n2k02updater.files provider during manifest merging.
 */
class LegacyUpdateFileProvider : FileProvider()
