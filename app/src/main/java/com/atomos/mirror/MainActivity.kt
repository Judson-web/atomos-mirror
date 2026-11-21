package com.atomos.mirror

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject

private data class ReceiverSnapshot(
    val status: String,
    val address: String,
    val paired: Boolean,
    val devices: List<Pair<String, String>>
)

private enum class AppPage { HOME, PAIR, DEVICES, SETTINGS }

class MainActivity : ComponentActivity() {
    private var receiverService: MirrorReceiverService? = null
    private var bound = false
    private var shareWithMicrophone = false
    private var pendingShareRequest = false
    private val receiverPrefs by lazy { getSharedPreferences("atomos_mirror", MODE_PRIVATE) }
    private lateinit var projectionManager: MediaProjectionManager
    private val serviceRevision = mutableIntStateOf(0)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            receiverService = (binder as? MirrorReceiverService.LocalBinder)?.getService()
            bound = true
            serviceRevision.intValue++
            if (pendingShareRequest && receiverService != null) {
                pendingShareRequest = false
                requestScreenCapture()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            bound = false
            serviceRevision.intValue++
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val token = receiverService?.approvedPairingToken()
                ?: null
            if (!token.isNullOrBlank()) {
                val shareIntent = Intent(this, AndroidScreenShareService::class.java)
                    .putExtra(AndroidScreenShareService.EXTRA_PROJECTION, result.data)
                    .putExtra(AndroidScreenShareService.EXTRA_PAIRING_TOKEN, token)
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
                primary = Color(0xFF86A9FF),
                background = Color(0xFF090D18),
                surface = Color(0xFF121B2C),
                secondary = Color(0xFF75DDB7)
            )) {
                MirrorApp(
                    initialHosting = receiverPrefs.getBoolean("receiver_enabled", false),
                    initialMicrophone = receiverPrefs.getBoolean("microphone_enabled", false),
                    initialAutoStart = receiverPrefs.getBoolean("auto_start_receiver", false),
                    serviceRevision = serviceRevision.intValue,
                    snapshotProvider = { receiverSnapshot() },
                    onStart = { startReceiver() },
                    onStop = { stopReceiver() },
                    onPairingQr = { payload -> approvePairingQr(payload) },
                    onShareAndroid = { withMicrophone ->
                        shareWithMicrophone = withMicrophone
                        receiverPrefs.edit().putBoolean("microphone_enabled", withMicrophone).apply()
                        if (receiverService?.approvedPairingToken().isNullOrBlank() &&
                            receiverPrefs.getString("pairing_token", null).isNullOrBlank()) {
                            pendingShareRequest = false
                        } else requestScreenCapture()
                    },
                    onStopAndroid = {
                        stopService(Intent(this, AndroidScreenShareService::class.java)
                            .setAction(AndroidScreenShareService.ACTION_STOP))
                    },
                    onMicrophoneChange = { shareWithMicrophone = it; receiverPrefs.edit().putBoolean("microphone_enabled", it).apply() },
                    onAutoStartChange = { receiverPrefs.edit().putBoolean("auto_start_receiver", it).apply() }
                )
            }
        }
        if (receiverPrefs.getBoolean("auto_start_receiver", false)) startReceiver()
    }

    private fun receiverSnapshot(): ReceiverSnapshot {
        val service = receiverService
        val ready = service?.statusText() ?: "Receiver stopped"
        val devices = service?.connectedDevices()?.let { json ->
            (0 until json.length()).mapNotNull { index ->
                json.optJSONObject(index)?.let { it.optString("name") to it.optString("role") }
            }
        } ?: emptyList()
        val paired = !service?.approvedPairingToken().isNullOrBlank() ||
            !receiverPrefs.getString("pairing_token", null).isNullOrBlank()
        val address = if (service == null) "" else service.localAddress().let {
            if (it == "Connect to Wi-Fi" || it == "Unavailable") it else "http://$it:${service.httpPort}"
        }
        return ReceiverSnapshot(ready, address, paired, devices)
    }

    private fun approvePairingQr(raw: String): String {
        return try {
            val data = JSONObject(raw)
            if (data.optString("type") != "atomos-pair" || data.optInt("version") != 1)
                return "This is not an Atomos Mirror pairing QR."
            val service = receiverService ?: return "Start the receiver first, then scan again."
            val expectedHost = service.localAddress()
            val qrHost = data.optString("host")
            if (qrHost != expectedHost) return "This QR belongs to another network address. Refresh the QR on the connected PC and try again."
            val token = data.optString("token")
            if (!service.approvePairingToken(token)) return "This QR has expired. Generate a fresh QR on the computer."
            receiverPrefs.edit().remove("pairing_token").apply()
            serviceRevision.intValue++
            "Computer approved. Return to the computer and start mirroring."
        } catch (_: Exception) {
            "Could not read the pairing code. Try scanning the QR again."
        }
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
        serviceRevision.intValue++
    }

    private fun stopReceiver() {
        receiverPrefs.edit().putBoolean("receiver_enabled", false).remove("pairing_token").apply()
        pendingShareRequest = false
        stopService(Intent(this, AndroidScreenShareService::class.java).setAction(AndroidScreenShareService.ACTION_STOP))
        stopService(Intent(this, MirrorReceiverService::class.java).setAction(MirrorReceiverService.ACTION_STOP))
        if (bound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            bound = false
        }
        receiverService = null
        serviceRevision.intValue++
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            bound = false
        }
        receiverService = null
        super.onDestroy()
    }
}

@Composable
private fun MirrorApp(
    initialHosting: Boolean,
    initialMicrophone: Boolean,
    initialAutoStart: Boolean,
    serviceRevision: Int,
    snapshotProvider: () -> ReceiverSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPairingQr: (String) -> String,
    onShareAndroid: (Boolean) -> Unit,
    onStopAndroid: () -> Unit,
    onMicrophoneChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit
) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var hosting by remember { mutableStateOf(initialHosting) }
    var microphone by remember { mutableStateOf(initialMicrophone) }
    var autoStart by remember { mutableStateOf(initialAutoStart) }
    var snapshot by remember { mutableStateOf(snapshotProvider()) }
    var pairingMessage by remember { mutableStateOf("Scan the QR displayed on your computer to approve pairing.") }

    LaunchedEffect(hosting, serviceRevision) {
        while (true) {
            snapshot = snapshotProvider()
            delay(900)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF10192A)) {
                NavigationBarItem(selected = page == AppPage.HOME, onClick = { page = AppPage.HOME },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) }, label = { Text("Home") })
                NavigationBarItem(selected = page == AppPage.PAIR, onClick = { page = AppPage.PAIR },
                    icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) }, label = { Text("Pair") })
                NavigationBarItem(selected = page == AppPage.DEVICES, onClick = { page = AppPage.DEVICES },
                    icon = { Icon(Icons.Rounded.Devices, contentDescription = null) }, label = { Text("Devices") })
                NavigationBarItem(selected = page == AppPage.SETTINGS, onClick = { page = AppPage.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF172B4B), Color(0xFF090D18)))).padding(padding)) {
            when (page) {
                AppPage.HOME -> HomePage(
                    hosting = hosting,
                    snapshot = snapshot,
                    microphone = microphone,
                    onMicrophone = { microphone = it; onMicrophoneChange(it) },
                    onStart = { onStart(); hosting = true },
                    onStop = { onStop(); hosting = false },
                    onShare = { onShareAndroid(microphone) },
                    onStopShare = onStopAndroid,
                    onGoPair = { page = AppPage.PAIR }
                )
                AppPage.PAIR -> PairPage(
                    hosting = hosting,
                    message = pairingMessage,
                    onStart = { onStart(); hosting = true },
                    onScanned = { raw -> pairingMessage = onPairingQr(raw) }
                )
                AppPage.DEVICES -> DevicesPage(snapshot)
                AppPage.SETTINGS -> SettingsPage(
                    microphone = microphone,
                    autoStart = autoStart,
                    onMicrophone = { microphone = it; onMicrophoneChange(it) },
                    onAutoStart = { autoStart = it; onAutoStartChange(it) }
                )
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF536FE0), modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("A", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF9EADC7), fontSize = 12.sp)
        }
    }
}

@Composable
private fun HomePage(
    hosting: Boolean,
    snapshot: ReceiverSnapshot,
    microphone: Boolean,
    onMicrophone: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onStopShare: () -> Unit,
    onGoPair: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader("Atomos Mirror", "Two-way local screen streaming")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (hosting) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null,
                tint = if (hosting) Color(0xFF75DDB7) else Color.LightGray)
            Spacer(Modifier.width(8.dp))
            Text(if (hosting) "Receiver online" else "Receiver offline", color = if (hosting) Color(0xFF75DDB7) else Color(0xFF9EADC7), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(snapshot.status, color = Color(0xFF8999B4), fontSize = 11.sp)
        }
        Text("Your devices. One screen.", fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
        Text("Mirror across your private Wi-Fi network. No cloud account required.", color = Color(0xFF9EADC7), lineHeight = 22.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCAL RECEIVER", color = Color(0xFF8EAEFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(if (hosting) snapshot.address.ifBlank { "Finding local network address…" } else "Start the receiver to make this phone discoverable.", color = Color(0xFFC8D8FF), fontWeight = FontWeight.SemiBold)
                Button(onClick = { if (hosting) onStop() else onStart() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(if (hosting) "Stop receiver" else "Start receiver")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pair your computer", fontWeight = FontWeight.Bold)
                        Text(if (snapshot.paired) "Computer approved" else "Scan the QR code shown on your computer", color = Color(0xFF9EADC7), fontSize = 12.sp)
                    }
                    TextButton(onClick = onGoPair) { Text("Open") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include microphone", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Optional voice audio when sharing your phone", color = Color(0xFF8999B4), fontSize = 11.sp)
                    }
                    Switch(checked = microphone, onCheckedChange = onMicrophone)
                }
                Button(onClick = onShare, enabled = hosting && snapshot.paired, modifier = Modifier.fillMaxWidth()) {
                    Text("Share this phone screen")
                }
                OutlinedButton(onClick = onStopShare, modifier = Modifier.fillMaxWidth()) { Text("Stop phone screen sharing") }
            }
        }
        Text("Use Pair to scan the QR. Use Devices to see active connections.", color = Color(0xFF71819D), fontSize = 12.sp)
    }
}

@Composable
private fun PairPage(hosting: Boolean, message: String, onStart: () -> Unit, onScanned: (String) -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { cameraError = ""; scanning = true }
        else cameraError = "Camera permission is needed to scan the pairing QR."
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader("Secure pairing", "Scan once. No PINs to type.")
        Text("Keep the receiver running, open the pairing QR on your PC, then scan it here.", color = Color(0xFF9EADC7), lineHeight = 21.sp)
        if (!hosting) Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start receiver") }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    QrScanner(
                        onResult = { raw -> scanning = false; onScanned(raw) },
                        onError = { cameraError = it }
                    )
                    TextButton(onClick = { scanning = false }) { Text("Cancel scan") }
                } else {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF1D2C47), modifier = Modifier.size(92.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.QrCodeScanner, null, tint = Color(0xFF86A9FF), modifier = Modifier.size(48.dp)) }
                    }
                    Button(onClick = {
                        if (!hosting) onStart()
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            scanning = true
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Scan pairing QR") }
                }
                Text(message, color = if (message.startsWith("Computer approved")) Color(0xFF75DDB7) else Color(0xFF9EADC7), fontSize = 12.sp)
                if (cameraError.isNotBlank()) Text(cameraError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        Text("Pairing codes expire after three minutes. Generate a fresh QR if the scan is rejected.", color = Color(0xFF71819D), fontSize = 12.sp)
    }
}

@Composable
private fun DevicesPage(snapshot: ReceiverSnapshot) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Your devices", "Live local connections")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("${snapshot.devices.size} CONNECTED", color = Color(0xFF8EAEFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (snapshot.devices.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Nothing connected yet", fontWeight = FontWeight.SemiBold)
                    Text("Scan the pairing QR, then start a stream from either device.", color = Color(0xFF9EADC7), fontSize = 12.sp)
                } else {
                    LazyColumn {
                        items(snapshot.devices) { device ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Devices, null, tint = Color(0xFF86A9FF))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.first, fontWeight = FontWeight.SemiBold)
                                    Text(device.second.replaceFirstChar { it.uppercase() }, color = Color(0xFF9EADC7), fontSize = 11.sp)
                                }
                                Text("●", color = Color(0xFF75DDB7))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    microphone: Boolean,
    autoStart: Boolean,
    onMicrophone: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Settings", "App preferences and information")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingSwitch("Microphone audio", "Include phone microphone when screen sharing", microphone, onMicrophone)
                HorizontalDivider(color = Color(0xFF293750))
                SettingSwitch("Start receiver on launch", "Keep the local receiver ready when you open the app", autoStart, onAutoStart)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121B2C)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ATOMOS MIRROR", color = Color(0xFF8EAEFF), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Version 1.1.0", fontWeight = FontWeight.SemiBold)
                Text("Local-network screen mirroring. WebRTC media is sent directly between paired devices. Android internal playback audio is not supported yet.", color = Color(0xFF9EADC7), fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color(0xFF9EADC7), fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
