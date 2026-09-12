package com.offgrid.app.data.repository

import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.model.SOSAlert
import com.offgrid.app.data.model.SOSStatus
import com.offgrid.app.data.transport.CommunicationTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Prototype SOS layer. If the real linked device is connected, the alert is genuinely delivered
 * to it as an EMERGENCY message (visible in their chat). Reach/helper counts against the
 * simulated roster are staged over time to make broadcast progress visible in a demo — this is
 * explicitly the "functional prototype layer" called for rather than a real mesh SOS network.
 */
class EmergencyRepository(
    private val transport: CommunicationTransport,
    private val messaging: MessagingRepository,
    private val selfId: String,
) {
    private val _sos = MutableStateFlow<SOSAlert?>(null)
    val sos: StateFlow<SOSAlert?> = _sos.asStateFlow()

    fun activate(scope: CoroutineScope, message: String) {
        _sos.value = SOSAlert(senderId = selfId, message = message, status = SOSStatus.BROADCASTING)
        scope.launch {
            val peer = transport.discoveredNodes.value.firstOrNull { !it.isSimulated }
            if (peer != null && peer.status == NodeStatus.CONNECTED) {
                messaging.sendEmergency(peer, "SOS: $message")
            }
            val reachable = transport.discoveredNodes.value.count { it.isSimulated || it.status == NodeStatus.CONNECTED }
            for (step in 1..reachable) {
                delay(500)
                if (_sos.value?.status == SOSStatus.CANCELLED) return@launch
                _sos.value = _sos.value?.copy(
                    status = SOSStatus.ACTIVE,
                    nodesReached = step,
                    helpersFound = minOf(step, 2),
                )
            }
        }
    }

    fun cancel() {
        _sos.value = _sos.value?.copy(status = SOSStatus.CANCELLED)
    }

    fun clear() {
        _sos.value = null
    }
}
