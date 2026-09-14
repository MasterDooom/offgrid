package com.offgrid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.runtime.OffGridRuntime
import com.offgrid.app.service.OffGridNetworkService
import com.offgrid.app.ui.OffGridApp

class MainActivity : ComponentActivity() {
    private lateinit var identity: IdentityManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val nearbyGranted = result
            .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            .values
            .all { it }
        if (nearbyGranted) {
            startMeshService()
        } else {
            Toast.makeText(
                this,
                "Nearby device permission is required for offline discovery.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        OffGridRuntime.initialize(applicationContext)
        identity = OffGridRuntime.identity

        setContent {
            OffGridApp(
                identity = identity,
                transport = OffGridRuntime.transport,
                messaging = OffGridRuntime.messaging,
                emergency = OffGridRuntime.emergency,
            )
        }

        if (hasRequiredPermissions()) startMeshService()
        else permissionLauncher.launch(requiredPermissions())
    }

    private fun startMeshService() {
        // Start while the Activity is visible; newer Android versions restrict background FGS starts.
        startForegroundService(
            this,
            Intent(this, OffGridNetworkService::class.java),
        )
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Wi-Fi P2P discovery/connect uses ACCESS_FINE_LOCATION on Android 12L and below.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()
}
