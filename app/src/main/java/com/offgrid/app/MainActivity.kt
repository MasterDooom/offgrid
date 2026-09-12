package com.offgrid.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.ui.OffGridApp

class MainActivity : ComponentActivity() {
    private lateinit var identity: IdentityManager
    private lateinit var transport: MockCommunicationTransport

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startTransport()
        } else {
            Toast.makeText(
                this,
                "Nearby permission is required for offline device discovery.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identity = IdentityManager(applicationContext)
        transport = MockCommunicationTransport(applicationContext)

        setContent {
            val scope = rememberCoroutineScope()
            val messaging = remember { MessagingRepository(transport, identity.nodeId, scope) }
            val emergency = remember { EmergencyRepository(transport, messaging, identity.nodeId) }

            LaunchedEffect(Unit) {
                // Transport startup is triggered after the Android nearby permissions are granted.
            }

            OffGridApp(
                identity = identity,
                transport = transport,
                messaging = messaging,
                emergency = emergency,
            )
        }

        if (hasNearbyPermissions()) {
            startTransport()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun startTransport() {
        // Nearby Connections is a real device-to-device transport. It uses Bluetooth/BLE and
        // Wi-Fi-capable peer-to-peer links; no internet server is involved in the message path.
        lifecycleScope.launchWhenStarted {
            transport.start(identity.nodeId, identity.displayName)
        }
    }

    private fun hasNearbyPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= 29) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }
}
