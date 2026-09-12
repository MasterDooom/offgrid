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
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.ui.chat.ChatScreen
import com.offgrid.app.ui.emergency.EmergencyScreen
import com.offgrid.app.ui.home.HomeScreen
import com.offgrid.app.ui.profile.ProfileScreen
import com.offgrid.app.ui.settings.DemoSetupScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed class Screen {
    data object Home : Screen()
    data class Profile(val node: Node) : Screen()
    data class Chat(val node: Node) : Screen()
    data object Emergency : Screen()
    data object DemoSetup : Screen()
}

private val OffGridColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF00201A),
    secondary = Color(0xFF60A5FA),
    background = Color(0xFF0B1210),
    surface = Color(0xFF121B18),
    surfaceVariant = Color(0xFF1B2622),
    error = Color(0xFFEF4444),
)

@Composable
fun OffGridApp(
    identity: IdentityManager,
    transport: MockCommunicationTransport,
    messaging: MessagingRepository,
    emergency: EmergencyRepository,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    val nodes by transport.discoveredNodes.collectAsState()
    val linkState by transport.linkState.collectAsState()
    val transportStatus by transport.transportStatus.collectAsState()
    val conversations by messaging.conversations.collectAsState()
    val sos by emergency.sos.collectAsState()

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    fun safeLaunch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Never let a failed nearby/UI operation crash the app.
            }
        }
    }

    fun openChat(node: Node) {
        // The chat is opened immediately. Connection work happens asynchronously.
        screen = Screen.Chat(node)
        safeLaunch {
            if (node.status != NodeStatus.CONNECTED) {
                transport.connectToDevice(node)
            }
        }
    }

    MaterialTheme(colorScheme = OffGridColors) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                identity = identity,
                nodes = nodes,
                linkState = linkState,
                transportStatus = transportStatus,
                conversations = conversations,
                onSelectNode = { node -> screen = Screen.Profile(node) },
                onOpenConversation = { node -> openChat(node) },
                onDiscover = { safeLaunch { transport.discoverDevices() } },
                onEmergency = { screen = Screen.Emergency },
                onOpenSettings = { screen = Screen.DemoSetup },
            )

            is Screen.Profile -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                ProfileScreen(
                    node = liveNode,
                    onBack = { screen = Screen.Home },
                    onMessage = { openChat(liveNode) },
                )
            }

            is Screen.Chat -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                val conversation = conversations[liveNode.id]
                // Connection state is per selected endpoint, not global. This matters when A is
                // connected to B while C is also visible in the nearby list.
                val canSend = liveNode.status == NodeStatus.CONNECTED && !liveNode.isSimulated
                ChatScreen(
                    node = liveNode,
                    messages = conversation?.messages ?: emptyList(),
                    selfId = identity.nodeId,
                    canSend = canSend,
                    onBack = { screen = Screen.Home },
                    onSend = { text ->
                        safeLaunch {
                            // The send button is only enabled for a connected selected peer.
                            // If that peer drops between frames, send() returns a failure rather
                            // than throwing through the UI.
                            messaging.send(liveNode, text)
                        }
                    },
                )
            }

            is Screen.Emergency -> EmergencyScreen(
                nodes = nodes,
                sos = sos,
                onBack = { screen = Screen.Home },
                onActivate = { message -> safeLaunch { emergency.activate(scope, message) } },
                onCancel = { emergency.cancel() },
            )

            is Screen.DemoSetup -> DemoSetupScreen(
                identity = identity,
                linkState = linkState,
                onBack = { screen = Screen.Home },
                onApply = { _, _, _ -> screen = Screen.Home },
                onTestConnection = { safeLaunch { transport.discoverDevices() } },
            )
        }
    }
}
