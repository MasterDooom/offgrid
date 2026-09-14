package com.offgrid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

private val OffGridColors = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF07111F),
    primaryContainer = Color(0xFF172A45),
    onPrimaryContainer = Color(0xFFDCEAFF),
    secondary = Color(0xFFFF6B9A),
    onSecondary = Color(0xFF24000E),
    secondaryContainer = Color(0xFF401526),
    onSecondaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF080A10),
    surface = Color(0xFF0E1118),
    surfaceVariant = Color(0xFF171B24),
    onSurface = Color(0xFFF5F7FA),
    onSurfaceVariant = Color(0xFF9AA4B2),
    error = Color(0xFFFF5C63),
    onError = Color.White,
)

@Composable
fun OffGridApp(
    identity: IdentityManager,
    transport: CommunicationTransport,
    messaging: MessagingRepository,
    emergency: EmergencyRepository,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()
    val nodes by transport.discoveredNodes.collectAsState()
    val linkState by transport.linkState.collectAsState()
    val statusFlow = remember(transport) {
        (transport as? WifiDirectCommunicationTransport)?.transportStatus
            ?: MutableStateFlow("Offline transport")
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

    fun safeLaunch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // UI actions must not crash the app.
            }
        }
    }

    fun openChat(node: Node) {
        // The Android P2P device address is stable for the discovered row even after the
        // handshake replaces its logical ID. Always refresh by physical row ID here.
        val liveNode = nodes.find { it.id == node.id } ?: node
        screen = Screen.Chat(liveNode)
        val relayAvailable = nodes.any {
            it.status == NodeStatus.CONNECTED && !it.isSimulated && it.id != liveNode.id
        }
        if (liveNode.status != NodeStatus.CONNECTED && !relayAvailable) {
            safeLaunch { transport.connectToDevice(liveNode) }
        }
    }

    MaterialTheme(colorScheme = OffGridColors) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                identity = identity,
                nodes = nodes,
                linkState = linkState,
                transportStatus = transportStatus,
                onSelectNode = { screen = Screen.Profile(it) },
                onOpenConversation = { openChat(it) },
                onDiscover = { safeLaunch { transport.discoverDevices() } },
                onEmergency = { screen = Screen.Emergency },
                onOpenSettings = { screen = Screen.DemoSetup },
                onOpenMessages = { screen = Screen.Messages },
            )
            Screen.Messages -> MessagesScreen(
                conversations = conversations,
                onOpenConversation = { openChat(it) },
                onHome = { screen = Screen.Home },
                onSettings = { screen = Screen.DemoSetup },
            )
            is Screen.Profile -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                ProfileScreen(liveNode, { screen = Screen.Home }, { openChat(liveNode) })
            }
            is Screen.Chat -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                val conversation = conversations[liveNode.logicalId]
                val relayAvailable = nodes.any {
                    it.status == NodeStatus.CONNECTED && !it.isSimulated && it.id != liveNode.id
                }
                ChatScreen(
                    liveNode,
                    conversation?.messages ?: emptyList(),
                    identity.nodeId,
                    canSend = !liveNode.isSimulated &&
                        (liveNode.status == NodeStatus.CONNECTED || relayAvailable),
                    onBack = { screen = Screen.Home },
                    onSend = { safeLaunch { messaging.send(liveNode, it) } },
                    transportStatus = transportStatus,
                )
            }
            Screen.Network -> NetworkScreen(
                nodes, identity.nodeId, identity.displayName,
                { screen = Screen.Home }, { screen = Screen.Profile(it) },
                transportStatus, { screen = Screen.Home }, { screen = Screen.Messages },
            )
            Screen.Emergency -> EmergencyScreen(
                nodes, sos,
                { screen = Screen.Home },
                { safeLaunch { emergency.activate(scope, it) } },
                { emergency.cancel() },
            )
            Screen.DemoSetup -> DemoSetupScreen(
                identity,
                linkState,
                { screen = Screen.Home },
                { _, _, _ -> screen = Screen.Home },
                { safeLaunch { transport.discoverDevices() } },
                { newName -> identity.displayName = newName },
            )
        }
    }
}
