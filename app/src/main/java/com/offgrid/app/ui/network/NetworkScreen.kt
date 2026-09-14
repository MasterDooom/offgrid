package com.offgrid.app.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlin.math.cos
import kotlin.math.sin

private val Navy = Color(0xFF10213A)
private val Blue = Color(0xFF1769FF)
private val Green = Color(0xFF08A66A)
private val MapBg = Color(0xFF132333)
private val MapLine = Color(0xFF9FB7C8)
private val SoftBlue = Color(0xFFE8F0FF)

@Composable
fun NetworkScreen(
    nodes: List<Node>,
    selfId: String,
    selfName: String,
    onBack: () -> Unit,
    onSelectNode: (Node) -> Unit,
    transportStatus: String = "",
    onHome: (() -> Unit)? = null,
    onMessages: (() -> Unit)? = null,
) {
    var selected by androidx.compose.runtime.remember { mutableStateOf<Node?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(false, { onHome?.invoke() ?: onBack() }, icon = { Text("⌂") }, label = { Text("Home") })
                NavigationBarItem(false, { onMessages?.invoke() ?: onBack() }, icon = { Text("□") }, label = { Text("Messages") })
                NavigationBarItem(true, {}, icon = { Text("⌘", color = Blue) }, label = { Text("Network") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Network", color = Navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("See the people around you.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text("⚙", color = Navy, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkTab("Local Map", true)
                NetworkTab("List", false)
                NetworkTab("Diagnostics", false)
            }
            MapCard(nodes, selfId, selfName, { node -> selected = node; onSelectNode(node) })
            if (selected != null) {
                NodeDetail(selected!!, onClose = { selected = null })
            } else {
                Text("Nearby Devices", color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                    items(nodes, key = { it.logicalId }) { node -> DeviceRow(node) { selected = node; onSelectNode(node) } }
                }
            }
        }
    }
}

@Composable private fun NetworkTab(label: String, selected: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = if (selected) Color.White else MaterialTheme.colorScheme.surfaceVariant, tonalElevation = if (selected) 2.dp else 0.dp) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) Blue else Navy, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun MapCard(nodes: List<Node>, selfId: String, selfName: String, onSelect: (Node) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(410.dp)) {
        val cardWidth = maxWidth
        Surface(Modifier.fillMaxSize(), RoundedCornerShape(18.dp), color = MapBg) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height * .47f)
                    val r = minOf(size.width, size.height) * .37f
                    for (i in 1..4) drawCircle(MapLine.copy(alpha = .16f), r * i / 4f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    val visible = nodes.take(5)
                    visible.forEachIndexed { i, _ ->
                        val a = i.toDouble() / maxOf(visible.size, 1) * Math.PI * 2 - Math.PI / 2
                        drawLine(MapLine.copy(alpha = .8f), c, Offset((c.x + cos(a) * r * .82).toFloat(), (c.y + sin(a) * r * .82).toFloat()), 1.dp.toPx(), StrokeCap.Round)
                    }
                }
                Surface(Modifier.align(Alignment.TopStart).padding(12.dp), RoundedCornerShape(9.dp), Color(0xDD152536)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text("⌘  ${nodes.size} devices", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("⌘  ${nodes.maxOfOrNull { it.hops } ?: 0} hops (max)", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Surface(Modifier.align(Alignment.Center).size(72.dp), CircleShape, Blue) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("●", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                }
                Text("You", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).padding(top = 82.dp))
                nodes.take(5).forEachIndexed { i, node ->
                    val a = i.toDouble() / maxOf(nodes.take(5).size, 1) * Math.PI * 2 - Math.PI / 2
                    val x = (.5 + .37 * cos(a)).toFloat(); val y = (.47 + .38 * sin(a)).toFloat()
                    val connected = node.status == NodeStatus.CONNECTED
                    Surface(Modifier.align(Alignment.TopStart).padding(start = cardWidth * x - 30.dp, top = 410.dp * y - 30.dp).size(60.dp).clickable { onSelect(node) }, CircleShape, Color(0xCC183043)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(15.dp).background(if (connected) Green else Blue, CircleShape))
                            Text(node.name.take(8), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", color = Color(0xFFCFD8E3), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("0     50     100 m", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
                Text("◎", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp))
            }
        }
    }
}

@Composable private fun NodeDetail(node: Node, onClose: () -> Unit) {
    val connected = node.status == NodeStatus.CONNECTED
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), RoundedCornerShape(18.dp), Color.White, tonalElevation = 4.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Text(node.name.take(1).uppercase(), color = Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(node.name, color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (node.capabilities.contains(DeviceCapability.RELAY)) "Relay node · ${node.hops} hops" else "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text("×", color = Navy, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
            Spacer(Modifier.size(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailMetric("▥", "Signal", if (connected) "Good" else "—")
                DetailMetric("⌘", "Hop count", node.hops.toString())
                DetailMetric("▣", "Encryption", "AES-256")
            }
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.weight(1f), RoundedCornerShape(10.dp), SoftBlue) { Text("▣  Send message", color = Blue, modifier = Modifier.padding(vertical = 11.dp), style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.size(8.dp))
                Surface(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.surfaceVariant) { Text("•••", color = Navy, modifier = Modifier.padding(11.dp)) }
            }
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("⌁  Estimated proximity\n    ~120 m", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text("Last seen\nJust now", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun DetailMetric(icon: String, label: String, value: String) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(9.dp)) { Text(icon, color = Green); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun DeviceRow(node: Node, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).background(if (node.status == NodeStatus.CONNECTED) Color(0xFFDDF7EC) else SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).background(if (node.status == NodeStatus.CONNECTED) Green else Blue, CircleShape)) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(node.name, color = Navy, fontWeight = FontWeight.SemiBold); Text("${node.hops} hop${if (node.hops == 1) "" else "s"} · ${if (node.status == NodeStatus.CONNECTED) "Connected" else "Nearby"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }
}
