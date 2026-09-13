package com.offgrid.app.ui.network

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetworkScreen(
    nodes: List<Node>,
    selfId: String,
    selfName: String,
    onBack: () -> Unit,
    onSelectNode: (Node) -> Unit,
    transportStatus: String = "",
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Column(Modifier.weight(1f)) {
                Text("Network", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Live nearby mesh", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                Text("${nodes.size} nodes", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        NetworkMap(
            nodes = nodes,
            selfId = selfId,
            selfName = selfName,
            onSelectNode = onSelectNode,
            modifier = Modifier.fillMaxWidth().size(330.dp).padding(horizontal = 16.dp),
        )

        EncryptionDemoCard(
            transportStatus = transportStatus,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MESH STATUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(
                    transportStatus.ifBlank { "Listening for nearby OffGrid nodes…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (transportStatus.contains("Relay", ignoreCase = true) || transportStatus.contains("hops", ignoreCase = true)) {
                    Text(
                        "Relay path: each hop is decrypted locally, then re-encrypted for the next link.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "Nearby devices",
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(nodes, key = { it.id }) { node ->
                Surface(
                    onClick = { onSelectNode(node) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(node.status)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(node.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(node.logicalId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (node.capabilities.contains(DeviceCapability.RELAY)) "RELAY" else node.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (node.capabilities.contains(DeviceCapability.RELAY)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkMap(
    nodes: List<Node>,
    selfId: String,
    selfName: String,
    onSelectNode: (Node) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse by rememberInfiniteTransition(label = "network-pulse").animateFloat(
        initialValue = .55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val positions = remember(nodes.map { it.id }) {
        nodes.mapIndexed { index, node ->
            val angle = (index.toDouble() / maxOf(nodes.size, 1)) * Math.PI * 2.0 - Math.PI / 2.0
            node.id to Offset((0.5f + 0.34f * cos(angle)).toFloat(), (0.5f + 0.34f * sin(angle)).toFloat())
        }.toMap()
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * .34f
            for (i in 1..3) {
                drawCircle(
                    color = primary.copy(alpha = .045f),
                    radius = radius * i / 3f,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
            positions.values.forEach { p ->
                drawLine(
                    color = secondary.copy(alpha = .22f),
                    start = center,
                    end = Offset(p.x * size.width, p.y * size.height),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.Center).size(82.dp),
            shape = CircleShape,
            color = primary.copy(alpha = .14f),
            tonalElevation = 3.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("◉", color = primary, style = MaterialTheme.typography.titleLarge)
                Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        nodes.forEachIndexed { index, node ->
            val angle = (index.toDouble() / maxOf(nodes.size, 1)) * Math.PI * 2.0 - Math.PI / 2.0
            val x = (0.5f + 0.34f * cos(angle)).toFloat()
            val y = (0.5f + 0.34f * sin(angle)).toFloat()
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = (x * 300 - 29).dp, top = (y * 300 - 29).dp).size(58.dp),
                shape = CircleShape,
                color = if (node.status == NodeStatus.CONNECTED) secondary.copy(alpha = .18f) else surface,
                tonalElevation = 3.dp,
                onClick = { onSelectNode(node) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("•", color = secondary, style = MaterialTheme.typography.titleLarge)
                    Text(node.name.take(8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Text(
            "${nodes.count { it.status == NodeStatus.CONNECTED }} linked · pulse ${((pulse * 100).toInt())}%",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(status: NodeStatus) {
    val color = when (status) {
        NodeStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        NodeStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
        NodeStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary.copy(alpha = .75f)
        NodeStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(11.dp).background(color, CircleShape))
}
