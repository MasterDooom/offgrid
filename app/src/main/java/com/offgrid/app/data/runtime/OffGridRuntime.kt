package com.offgrid.app.data.runtime

import android.content.Context
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.CommunicationTransport
import com.offgrid.app.data.transport.MockCommunicationTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide OffGrid networking state.
 *
 * The foreground service and the Compose UI use the same transport/repositories so the network
 * does not disappear when MainActivity is stopped or recreated.
 *
 * Nearby Connections remains the stable/default physical transport. Wi-Fi Direct is kept as a
 * separate transport module for explicit range testing instead of replacing the known-good app
 * path before it has been validated on the target phones.
 */
object OffGridRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var initialized = false

    lateinit var identity: IdentityManager
        private set

    lateinit var transport: CommunicationTransport
        private set

    lateinit var messaging: MessagingRepository
        private set

    lateinit var emergency: EmergencyRepository
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext
        identity = IdentityManager(appContext)
        transport = MockCommunicationTransport(appContext)
        messaging = MessagingRepository(transport, identity.nodeId, scope)
        emergency = EmergencyRepository(transport, messaging, identity.nodeId)
        initialized = true
    }
}
