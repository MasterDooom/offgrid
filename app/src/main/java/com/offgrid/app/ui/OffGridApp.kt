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
import com.offgrid.app.data.repository.EmergencyRepository
import com.offgrid.app.data.repository.IdentityManager
import com.offgrid.app.data.repository.MessagingRepository
import com.offgrid.app.data.transport.MockCommunicationTransport
import com.offgrid.app.ui.chat.ChatScreen
import com.offgrid.app.ui.emergency.EmergencyScreen
import com.offgrid.app.ui.home.HomeScreen
import com.offgrid.app.ui.profile.ProfileScreen
import com.offgrid.app.ui.settings.DemoSetupScreen
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
    val conversations by messaging.conversations.collectAsState()
    val sos by emergency.sos.collectAsState()

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    MaterialTheme(colorScheme = OffGridColors) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                identity = identity,
                nodes = nodes,
                linkState = linkState,
                conversations = conversations,
                onSelectNode = { node -> screen = Screen.Profile(node) },
                onOpenConversation = { node ->
                    scope.launch {
                        transport.connectToDevice(node)
                        screen = Screen.Chat(node)
                    }
                },
                onDiscover = { scope.launch { transport.discoverDevices() } },
                onEmergency = { screen = Screen.Emergency },
                onOpenSettings = { screen = Screen.DemoSetup },
            )

            is Screen.Profile -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                ProfileScreen(
                    node = liveNode,
                    onBack = { screen = Screen.Home },
                    onMessage = {
                        scope.launch {
                            transport.connectToDevice(liveNode)
                            screen = Screen.Chat(liveNode)
                        }
                    },
                )
            }

            is Screen.Chat -> {
                val liveNode = nodes.find { it.id == current.node.id } ?: current.node
                val conversation = conversations[liveNode.id]
                val canSend = linkState == LinkState.CONNECTED && !liveNode.isSimulated
                ChatScreen(
                    node = liveNode,
                    messages = conversation?.messages ?: emptyList(),
                    selfId = identity.nodeId,
                    canSend = canSend,
                    onBack = { screen = Screen.Home },
                    onSend = { text -> scope.launch { messaging.send(liveNode, text) } },
                )
            }

            is Screen.Emergency -> EmergencyScreen(
                nodes = nodes,
                sos = sos,
                onBack = { screen = Screen.Home },
                onActivate = { message -> emergency.activate(scope, message) },
                onCancel = { emergency.cancel() },
            )

            is Screen.DemoSetup -> DemoSetupScreen(
                identity = identity,
                linkState = linkState,
                onBack = { screen = Screen.Home },
                onApply = { _, _, _ -> screen = Screen.Home },
                onTestConnection = { scope.launch { transport.discoverDevices() } },
            )
        }
    }
}
