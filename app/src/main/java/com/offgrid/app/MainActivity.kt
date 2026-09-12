package com.offgrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.ui.OffGridApp

class MainActivity : ComponentActivity() {
    private lateinit var identity: IdentityManager
    private lateinit var transport: MockCommunicationTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = IdentityManager(applicationContext)
        transport = MockCommunicationTransport(
            selfPort = identity.selfPort,
            peerHost = identity.peerHost,
            peerPort = identity.peerPort,
        )

        setContent {
            val scope = rememberCoroutineScope()
            val messaging = remember { MessagingRepository(transport, identity.nodeId, scope) }
            val emergency = remember { EmergencyRepository(transport, messaging, identity.nodeId) }

            LaunchedEffect(Unit) { transport.start(identity.nodeId, identity.displayName) }

            OffGridApp(identity = identity, transport = transport, messaging = messaging, emergency = emergency)
        }
    }

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }
}
