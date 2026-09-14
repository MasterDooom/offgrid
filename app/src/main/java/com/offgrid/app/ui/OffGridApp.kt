package com.offgrid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.CommunicationTransport
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.data.transport.WifiDirectCommunicationTransport
import com.offgrid.app.ui.chat.ChatScreen
import com.offgrid.app.ui.emergency.EmergencyScreen
import com.offgrid.app.ui.home.HomeScreen
import com.offgrid.app.ui.messages.MessagesScreen
import com.offgrid.app.ui.network.NetworkScreen
import com.offgrid.app.ui.profile.ProfileScreen
import com.offgrid.app.ui.settings.DemoSetupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    data object Home : Screen()
    data object Messages : Screen()
    data class Profile(val node: Node) : Screen()
    data class Chat(val node: Node) : Screen()
    data object Emergency : Screen()
    data object DemoSetup : Screen()
    data object Network : Screen()
}

private val OffGridColors = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF), onPrimaryContainer = Color(0xFF12346B),
    secondary = Color(0xFF18A46B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9F8F1), onSecondaryContainer = Color(0xFF0B4A32),
    background = Color(0xFFF9FAFC), surface = Color.White,
    surfaceVariant = Color(0xFFF1F4F8), onSurface = Color(0xFF101828),
    onSurfaceVariant = Color(0xFF667085), error = Color(0xFFD92D3F),
)

@Composable
fun OffGridApp(identity: IdentityManager, transport: CommunicationTransport, messaging: MessagingRepository, emergency: EmergencyRepository) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    val nodes by transport.discoveredNodes.collectAsState()
    val linkState by transport.linkState.collectAsState()
    val statusFlow = remember(transport) {
        when (transport) {
            is WifiDirectCommunicationTransport -> transport.transportStatus
            is MockCommunicationTransport -> transport.transportStatus
            else -> MutableStateFlow("Transport active")
        }
    }
    val transportStatus by statusFlow.collectAsState()
    val conversations by messaging.conversations.collectAsState()
    val sos by emergency.sos.collectAsState()

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            Screen.Messages, Screen.Network, Screen.DemoSetup, Screen.Emergency -> Screen.Home
            is Screen.Profile, is Screen.Chat -> Screen.Home
            Screen.Home -> Screen.Home
        }
    }
    fun safeLaunch(block: suspend () -> Unit) { scope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { } } }
    fun openChat(node: Node) {
        val liveNode = nodes.find { it.logicalId == node.logicalId } ?: node
        screen = Screen.Chat(liveNode)
        val relayAvailable = nodes.any { it.status == NodeStatus.CONNECTED && !it.isSimulated && it.logicalId != liveNode.logicalId }
        if (liveNode.status != NodeStatus.CONNECTED && !relayAvailable) safeLaunch { transport.connectToDevice(liveNode) }
    }

    MaterialTheme(colorScheme = OffGridColors) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(identity, nodes, linkState, transportStatus, conversations,
                onSelectNode = { screen = Screen.Profile(it) }, onOpenConversation = { openChat(it) },
                onDiscover = { safeLaunch { transport.discoverDevices() } }, onEmergency = { screen = Screen.Emergency },
                onOpenSettings = { screen = Screen.DemoSetup }, onOpenNetwork = { screen = Screen.Network }, onOpenMessages = { screen = Screen.Messages })
            Screen.Messages -> MessagesScreen(conversations, { openChat(it) }, { screen = Screen.Home }, { screen = Screen.Network })
            is Screen.Profile -> { val liveNode = nodes.find { it.logicalId == current.node.logicalId } ?: current.node; ProfileScreen(liveNode, { screen = Screen.Home }, { openChat(liveNode) }) }
            is Screen.Chat -> {
                val liveNode = nodes.find { it.logicalId == current.node.logicalId } ?: current.node
                val conversation = conversations[liveNode.logicalId]
                val relayAvailable = nodes.any { it.status == NodeStatus.CONNECTED && !it.isSimulated && it.logicalId != liveNode.logicalId }
                ChatScreen(liveNode, conversation?.messages ?: emptyList(), identity.nodeId,
                    canSend = !liveNode.isSimulated && (liveNode.status == NodeStatus.CONNECTED || relayAvailable),
                    onBack = { screen = Screen.Home }, onSend = { safeLaunch { messaging.send(liveNode, it) } }, transportStatus = transportStatus)
            }
            Screen.Network -> NetworkScreen(nodes, identity.nodeId, identity.displayName, { screen = Screen.Home }, { screen = Screen.Profile(it) }, transportStatus, { screen = Screen.Home }, { screen = Screen.Messages })
            Screen.Emergency -> EmergencyScreen(nodes, sos, { screen = Screen.Home }, { safeLaunch { emergency.activate(scope, it) } }, { emergency.cancel() })
            Screen.DemoSetup -> DemoSetupScreen(identity, linkState, { screen = Screen.Home }, { _, _, _ -> screen = Screen.Home }, { safeLaunch { transport.discoverDevices() } }, { newName -> identity.displayName = newName })
        }
    }
}
