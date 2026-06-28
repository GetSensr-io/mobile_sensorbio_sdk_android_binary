package com.sensorbio.example

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.sensorbio.example.ui.AppRoot
import com.sensorbio.example.ui.SensorBioExampleTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* user choice */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent {
            SensorBioExampleTheme {
                AppRoot()
            }
        }
    }

    /**
     * The host owns the BLE runtime permission grant — the SDK assumes it's granted. On API 31+ that's
     * BLUETOOTH_SCAN + BLUETOOTH_CONNECT; on older releases BLE scanning needs ACCESS_FINE_LOCATION.
     */
    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }
}
