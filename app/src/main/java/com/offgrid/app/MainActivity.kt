package com.offgrid.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.ui.OffGridApp
import kotlinx.coroutines.launch

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
            // Keep repository collection on the Activity lifecycle rather than a composable
            // scope. Messaging must continue receiving packets while screens change.
            val messaging = androidx.compose.runtime.remember {
                MessagingRepository(transport, identity.nodeId, lifecycleScope)
            }
            val emergency = androidx.compose.runtime.remember {
                EmergencyRepository(transport, messaging, identity.nodeId)
            }

            OffGridApp(
                identity = identity,
                transport = transport,
                messaging = messaging,
                emergency = emergency,
            )
        }

        if (hasNearbyPermissions()) startTransport()
        else permissionLauncher.launch(requiredPermissions())
    }

    private fun startTransport() {
        lifecycleScope.launch {
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
