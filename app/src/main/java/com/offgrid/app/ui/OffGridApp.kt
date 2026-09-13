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
import com.offgrid.app.ui.network.NetworkScreen
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
    data object Network : Screen()
}

private val OffGridColors = lightColorScheme(
    primary = Color(0xFF2F80ED), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F2FF), onPrimaryContainer = Color(0xFF0B315E),
    secondary = Color(0xFFE84D9B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6F2), onSecondaryContainer = Color(0xFF5E153D),
    background = Color(0xFFF8FAFF), surface = Color.White, surfaceVariant = Color(0xFFF0F4FA),
    onSurface = Color(0xFF18212F), onSurfaceVariant = Color(0xFF657184), error = Color(0xFFD92D55),
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
            try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { }
        }
    }

    fun openChat(node: Node) {
        screen = Screen.Chat(node)
        safeLaunch { if (node.status != NodeStatus.CONNECTED) transport.connectToDevice(node) }
    }

    MaterialTheme(colorScheme = OffGridColors) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(identity, nodes, linkState, transportStatus, conversations,
                onSelectNode = { screen = Screen.Profile(it) },
                onOpenConversation = { openChat(it) }, onDiscover = { safeLaunch { transport.discoverDevices() } },
                onEmergency = { screen = Screen.Emergency }, onOpenSettings = { screen = Screen.DemoSetup },
                onOpenNetwork = { screen = Screen.Network })
            is Screen.Profile -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                ProfileScreen(liveNode, onBack = { screen = Screen.Home }, onMessage = { openChat(liveNode) })
            }
            is Screen.Chat -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                val conversation = conversations[liveNode.id]
                ChatScreen(
                    liveNode,
                    conversation?.messages ?: emptyList(),
                    identity.nodeId,
                    canSend = liveNode.status == NodeStatus.CONNECTED && !liveNode.isSimulated,
                    onBack = { screen = Screen.Home },
                    onSend = { safeLaunch { messaging.send(liveNode, it) } },
                    transportStatus = transportStatus,
                )
            }
            Screen.Network -> NetworkScreen(nodes, identity.nodeId, identity.displayName,
                onBack = { screen = Screen.Home }, onSelectNode = { screen = Screen.Profile(it) })
            Screen.Emergency -> EmergencyScreen(nodes, sos, onBack = { screen = Screen.Home },
                onActivate = { safeLaunch { emergency.activate(scope, it) } }, onCancel = { emergency.cancel() })
            Screen.DemoSetup -> DemoSetupScreen(identity, linkState, onBack = { screen = Screen.Home },
                onApply = { _, _, _ -> screen = Screen.Home }, onTestConnection = { safeLaunch { transport.discoverDevices() } })
        }
    }
}
