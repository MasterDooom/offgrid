package com.offgrid.app.data.runtime

import android.content.Context
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.CommunicationTransport
import com.offgrid.app.data.transport.WifiDirectCommunicationTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide OffGrid networking state.
 *
 * The foreground service and the Compose UI use the same transport/repositories so the network
 * does not disappear when MainActivity is stopped or recreated.
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
        transport = WifiDirectCommunicationTransport(appContext)
        messaging = MessagingRepository(transport, identity.nodeId, scope)
        emergency = EmergencyRepository(transport, messaging, identity.nodeId)
        initialized = true
    }
}
