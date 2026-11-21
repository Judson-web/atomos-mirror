package com.atomos.mirror

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
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
    private var receiverService: MirrorReceiverService? = null
    private var bound = false
    private var shareWithMicrophone = false
    private var pendingShareRequest = false
    private val receiverPrefs by lazy { getSharedPreferences("atomos_mirror", MODE_PRIVATE) }
    private lateinit var projectionManager: MediaProjectionManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            receiverService = (binder as? MirrorReceiverService.LocalBinder)?.getService()
            bound = true
            if (pendingShareRequest && receiverService != null) {
                pendingShareRequest = false
                requestScreenCapture()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            bound = false
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val pin = receiverService?.pairingPin
            if (!pin.isNullOrBlank()) {
                val shareIntent = Intent(this, AndroidScreenShareService::class.java)
                    .putExtra(AndroidScreenShareService.EXTRA_PROJECTION, result.data)
                    .putExtra(AndroidScreenShareService.EXTRA_PIN, pin)
                    .putExtra(AndroidScreenShareService.EXTRA_MICROPHONE, shareWithMicrophone)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(shareIntent) else startService(shareIntent)
            }
        }
    }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) shareWithMicrophone = false
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF86A9FF), background = Color(0xFF090D18),
                surface = Color(0xFF121B2C), secondary = Color(0xFF75DDB7)
            )) {
                MirrorHome(
                    initialHosting = receiverPrefs.getBoolean("receiver_enabled", false),
                    onStart = { startReceiver() },
                    onStop = { stopReceiver() },
                    onShareAndroid = { withMicrophone ->
                        shareWithMicrophone = withMicrophone
                        if (receiverService == null) {
                            pendingShareRequest = true
                            startReceiver()
                        } else requestScreenCapture()
                    },
                    onStopAndroid = { stopService(Intent(this, AndroidScreenShareService::class.java)) },
                    addressProvider = {
                        receiverService?.let { "http://" + it.localAddress() + ":" + it.httpPort + "\nPairing PIN: " + it.pairingPin } ?: ""
                    }
                )
            }
        }
        if (receiverPrefs.getBoolean("receiver_enabled", false)) startReceiver()
    }

    private fun requestScreenCapture() {
        if (shareWithMicrophone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startReceiver() {
        receiverPrefs.edit().putBoolean("receiver_enabled", true).apply()
        val intent = Intent(this, MirrorReceiverService::class.java).setAction(MirrorReceiverService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (!bound) bound = bindService(Intent(this, MirrorReceiverService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopReceiver() {
        receiverPrefs.edit().putBoolean("receiver_enabled", false).apply()
        pendingShareRequest = false
        stopService(Intent(this, AndroidScreenShareService::class.java))
        stopService(Intent(this, MirrorReceiverService::class.java).setAction(MirrorReceiverService.ACTION_STOP))
        if (bound) {
            try { unbindService(serviceConnection) } catch (_: Exception) { }
            bound = false
        }
        receiverService = null
    }

    override fun onDestroy() {
        // The receiver is deliberately not stopped here. Its foreground service owns
        // the LAN servers and discovery lifecycle independently of this Activity.
        if (bound) {
            try { unbindService(serviceConnection) } catch (_: Exception) { }
            bound = false
        }
        receiverService = null
        super.onDestroy()
    }
}

@Composable
private fun MirrorHome(initialHosting: Boolean, onStart: () -> Unit, onStop: () -> Unit, onShareAndroid: (Boolean) -> Unit, onStopAndroid: () -> Unit, addressProvider: () -> String) {
    var hosting by remember { mutableStateOf(initialHosting) }
    var microphoneEnabled by remember { mutableStateOf(false) }
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
                Column { Text("Atomos Mirror", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("Two-way local screen streaming", color = Color(0xFF9EADC7), fontSize = 12.sp) }
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
                        Text(if (!hosting) "OFFLINE" else if (address.startsWith("Receiver failed")) "ERROR" else if (address.startsWith("Starting")) "STARTING" else "ONLINE", color = if (hosting && !address.startsWith("Receiver failed")) Color(0xFF75DDB7) else Color(0xFF9EADC7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include microphone", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Optional live voice audio", color = Color(0xFF8999B4), fontSize = 11.sp)
                }
                Switch(checked = microphoneEnabled, onCheckedChange = { microphoneEnabled = it })
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onShareAndroid(microphoneEnabled) }, enabled = hosting, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Share this Android screen to Windows", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStopAndroid, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) { Text("Stop phone screen sharing") }
            Spacer(Modifier.height(18.dp))
            Text("DEVELOPMENT ROADMAP", color = Color(0xFF8EAEFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(8.dp))
            FeatureLine("Local receiver dashboard", "Implemented")
            FeatureLine("Automatic device discovery", "mDNS receiver advertisement enabled")
            FeatureLine("Windows → Android", "Browser WebRTC receiver integrated")
            FeatureLine("Android → Windows", "MediaProjection sender integrated, device test pending")
            FeatureLine("Microphone audio", "Optional, permission-gated")
            Spacer(Modifier.weight(1f))
            Text("ATOMOS MIRROR  ·  DEVELOPMENT BUILD  ·  v0.7.0", color = Color(0xFF687995), fontSize = 10.sp, letterSpacing = 1.sp)
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
