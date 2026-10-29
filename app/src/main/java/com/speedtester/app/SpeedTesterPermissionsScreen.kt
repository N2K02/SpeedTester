package com.speedtester.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal data class SpeedPermissionStatus(
    val precise: Boolean, val approximate: Boolean, val locationEnabled: Boolean,
    val wifiState: Boolean, val networkState: Boolean, val internet: Boolean,
) {
    val wifiIdentityAvailable: Boolean get() = precise && locationEnabled
}

private fun permissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun readSpeedPermissions(context: Context): SpeedPermissionStatus =
    SpeedPermissionStatus(
        precise = permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION),
        approximate = permissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        locationEnabled = runCatching {
            LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        }.getOrDefault(false),
        wifiState = permissionGranted(context, Manifest.permission.ACCESS_WIFI_STATE),
        networkState = permissionGranted(context, Manifest.permission.ACCESS_NETWORK_STATE),
        internet = permissionGranted(context, Manifest.permission.INTERNET)
    )

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, explanation: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(explanation) },
        trailingContent = { Text(if (granted) "Granted" else "Required",
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    )
}

@Composable
internal fun SpeedTesterPermissionsScreen(context: Context, modifier: Modifier = Modifier) {
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val status = remember(revision) { readSpeedPermissions(context) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    fun openAndroidSettings(action: String, uri: Uri? = null) {
        val intent = Intent(action).apply { if (uri != null) data = uri }
        runCatching { context.startActivity(intent) }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permission status", style = MaterialTheme.typography.titleLarge)
        Text("Internet access is required for speed tests. Precise location and enabled device Location are used for Wi-Fi SSID, BSSID and nearby network scans.",
            style = MaterialTheme.typography.bodyMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                PermissionStatusRow("Precise location", status.precise, "Identify the connected access point and scan nearby Wi-Fi.")
                PermissionStatusRow("Approximate location", status.approximate, "Companion permission required by Android.")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { request.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )) }, enabled = !status.precise) { Text("Grant access") }
                    OutlinedButton(onClick = { openAndroidSettings(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ) }) { Text("App settings") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                PermissionStatusRow("Device Location", status.locationEnabled, "Enable Android Location to access Wi-Fi scan results.")
                TextButton(onClick = { openAndroidSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text("Open Location settings") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                PermissionStatusRow("Wi-Fi state", status.wifiState, "Read Wi-Fi connection and signal information.")
                PermissionStatusRow("Network state", status.networkState, "Detect connection changes during tests.")
                PermissionStatusRow("Internet", status.internet, "Download and upload to selected test servers.")
            }
        }
        Text(if (status.wifiIdentityAvailable)
            "Wi-Fi identity access is ready. Network details refresh automatically on Home."
        else "Grant precise location and enable device Location to show Wi-Fi SSID/BSSID.",
            style = MaterialTheme.typography.bodySmall)
    }
}
