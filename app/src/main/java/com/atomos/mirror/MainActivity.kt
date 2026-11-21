package com.atomos.mirror

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var server: LocalReceiverServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF86A9FF), background = Color(0xFF090D18),
                surface = Color(0xFF121B2C), secondary = Color(0xFF75DDB7)
            )) {
                MirrorHome(
                    onStart = { startReceiver() },
                    onStop = { stopReceiver() },
                    addressProvider = { server?.let { "http://${it.localAddress()}:${it.listeningPort}" } ?: "" }
                )
            }
        }
    }

    private fun startReceiver() {
        if (server?.isAlive == true) return
        try { server = LocalReceiverServer(this).also { it.start() } }
        catch (_: Exception) { server = null }
    }

    private fun stopReceiver() { server?.stop(); server = null }

    override fun onDestroy() { stopReceiver(); super.onDestroy() }
}

@Composable
private fun MirrorHome(onStart: () -> Unit, onStop: () -> Unit, addressProvider: () -> String) {
    var hosting by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    LaunchedEffect(hosting) {
        while (hosting) { address = addressProvider(); delay(1000) }
        address = ""
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF172B4B), Color(0xFF090D18))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF536FE0), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("A", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Color.White) }
                }
                Spacer(Modifier.width(12.dp))
                Column { Text("Atomos Mirror", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("Local screen streaming", color = Color(0xFF9EADC7), fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                Icon(if (hosting) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null, tint = if (hosting) Color(0xFF75DDB7) else Color.LightGray)
            }
            Spacer(Modifier.height(44.dp))
            Text("YOUR DEVICES. ONE SCREEN.", color = Color(0xFF8EAEFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text("Mirror without\nlimits.", fontSize = 39.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Text("Stream across your local Wi-Fi network. No cloud account or internet connection required.", color = Color(0xFF9EADC7), lineHeight = 22.sp)
            Spacer(Modifier.height(28.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cast, null, tint = Color(0xFF86A9FF)); Spacer(Modifier.width(10.dp))
                        Text("Browser receiver", fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.weight(1f))
                        Text(if (hosting) "ONLINE" else "OFFLINE", color = if (hosting) Color(0xFF75DDB7) else Color(0xFF9EADC7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if (hosting) "Open this address on another device:" else "Start the local receiver to show its connection address.", color = Color(0xFF9EADC7), fontSize = 13.sp)
                    if (hosting) { Spacer(Modifier.height(12.dp)); Text(address.ifBlank { "Finding local address…" }, color = Color(0xFFC8D8FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { if (hosting) { onStop(); hosting = false } else { onStart(); hosting = true } }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                        Text(if (hosting) "Stop receiver" else "Start receiver", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("DEVELOPMENT ROADMAP", color = Color(0xFF8EAEFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(8.dp))
            FeatureLine("Local receiver dashboard", "Implemented")
            FeatureLine("Automatic device discovery", "Next integration")
            FeatureLine("WebRTC video and audio", "Streaming engine pending")
            FeatureLine("Internal audio + microphone", "Permission-aware capture pending")
            Spacer(Modifier.weight(1f))
            Text("ATOMOS MIRROR  ·  EARLY DEVELOPMENT  ·  v0.1.0", color = Color(0xFF687995), fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun FeatureLine(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1D2C47), modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("✓", color = Color(0xFF86A9FF), fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(11.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 11.sp, color = Color(0xFF8999B4)) }
    }
}
