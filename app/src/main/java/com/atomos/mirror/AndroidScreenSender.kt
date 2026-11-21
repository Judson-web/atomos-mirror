package com.atomos.mirror

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Captures the Android display with MediaProjection and sends it directly to a paired LAN peer. */
class AndroidScreenSender(
    private val context: Context,
    private val projectionData: Intent,
    private val pairingToken: String,
    private val microphoneEnabled: Boolean = false,
    private val audioProjectionData: Intent? = null
) {
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var microphoneSource: AudioSource? = null
    private var microphoneTrack: AudioTrack? = null
    private var playbackProjection: MediaProjection? = null
    private var playbackRecord: AudioRecord? = null
    @Volatile private var playbackRunning = false
    private var playbackThread: Thread? = null
    private var videoSender: RtpSender? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var egl: EglBase? = null
    private var socket: WebSocketClient? = null
    private val pendingRemoteIce = java.util.concurrent.ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var stopped = false
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectPending = AtomicBoolean(false)
    @Volatile private var reconnectAttempts = 0
    private val statsExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var previousBytesSent = -1L
    @Volatile private var previousFramesEncoded = -1L
    @Volatile private var previousStatsAt = 0L

    fun start() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        egl = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(emptyList())
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                send(JSONObject().put("type", "ice").put("candidate",
                    JSONObject().put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)))
            }
            override fun onIceCandidateError(event: IceCandidateErrorEvent) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        }) ?: error("Unable to create WebRTC peer connection")

        videoSource = factory!!.createVideoSource(true)
        videoTrack = factory!!.createVideoTrack("atomos-screen", videoSource!!)
        videoSender = peer!!.addTrack(videoTrack!!, listOf("atomos-screen-stream"))
        if (microphoneEnabled) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            microphoneSource = factory!!.createAudioSource(audioConstraints)
            microphoneTrack = factory!!.createAudioTrack("atomos-microphone", microphoneSource!!)
            peer!!.addTrack(microphoneTrack!!, listOf("atomos-audio-stream"))
        }

        textureHelper = SurfaceTextureHelper.create("AtomosScreenCapture", egl!!.eglBaseContext)
        capturer = ScreenCapturerAndroid(projectionData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stop() }
        })
        capturer!!.initialize(textureHelper!!, context, videoSource!!.capturerObserver)
        val display = context.resources.displayMetrics
        val sourceWidth = display.widthPixels.coerceAtLeast(2)
        val sourceHeight = display.heightPixels.coerceAtLeast(2)
        val scale = min(1.0, min(1920.0 / maxOf(sourceWidth, sourceHeight), 1080.0 / minOf(sourceWidth, sourceHeight)))
        val captureWidth = ((sourceWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
        val captureHeight = ((sourceHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
        capturer!!.startCapture(captureWidth, captureHeight, 30)

        connectSignalling()
        statsExecutor.scheduleAtFixedRate({ collectStats() }, 2, 1, TimeUnit.SECONDS)
    }

    private fun connectSignalling() {
        if (stopped) return
        val client = object : WebSocketClient(URI("ws://127.0.0.1:8766")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                reconnectAttempts = 0
                reconnectPending.set(false)
                send(JSONObject().put("type", "hello").put("role", "sender")
                    .put("pairingToken", pairingToken).put("name", "Android phone"))
            }
            override fun onMessage(message: String) {
                try {
                    val json = JSONObject(message)
                    when (json.optString("type")) {
                        "joined" -> {
                            startInternalAudioCapture()
                            val peers = json.optJSONArray("peers")
                            val receiverPresent = peers != null && (0 until peers.length()).any {
                                peers.optJSONObject(it)?.optString("role") == "receiver"
                            }
                            if (receiverPresent) createOffer()
                        }
                        "peer" -> if (json.optString("state") == "joined" && json.optString("role") == "receiver") createOffer()
                        "answer" -> peer?.setRemoteDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                while (pendingRemoteIce.isNotEmpty()) peer?.addIceCandidate(pendingRemoteIce.poll())
                            }
                        }, SessionDescription(SessionDescription.Type.ANSWER, json.getJSONObject("description").getString("sdp")))
                        "settings" -> applyStreamProfile(json.optString("profile", "balanced"))
                        "ice" -> {
                            val c = json.getJSONObject("candidate")
                            val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                            if (peer?.remoteDescription != null) peer?.addIceCandidate(candidate) else pendingRemoteIce.add(candidate)
                        }
                    }
                } catch (_: Exception) { }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { scheduleReconnect() }
            override fun onError(ex: Exception?) { scheduleReconnect() }
        }
        socket = client
        try { client.connect() } catch (_: Exception) { scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        if (stopped || !reconnectPending.compareAndSet(false, true)) return
        val waitSeconds = (1L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(20L)
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(5)
        reconnectExecutor.schedule({
            reconnectPending.set(false)
            if (!stopped) connectSignalling()
        }, waitSeconds, TimeUnit.SECONDS)
    }


    private fun applyStreamProfile(profile: String) {
        val config = when (profile) {
            "smooth" -> Triple(1280, 720, 30)
            "high" -> Triple(1920, 1080, 60)
            else -> Triple(1920, 1080, 30)
        }
        try { capturer?.changeCaptureFormat(config.first, config.second, config.third) } catch (_: Exception) { }
        val targetBitrate = when (profile) {
            "smooth" -> 2_500_000
            "high" -> 8_000_000
            else -> 5_000_000
        }
        try {
            val params = videoSender?.parameters
            params?.encodings?.forEach { it.maxBitrateBps = targetBitrate }
            if (params != null) videoSender?.setParameters(params)
        } catch (_: Exception) { }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || audioProjectionData == null || playbackRunning || stopped) return
        try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(Activity.RESULT_OK, audioProjectionData) ?: return
            playbackProjection = projection
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AUDIO_FRAME_BYTES * 4)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minimum * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                projection.stop()
                playbackProjection = null
                return
            }
            playbackRecord = record
            record.startRecording()
            playbackRunning = true
            playbackThread = Thread({
                val samples = ShortArray(AUDIO_FRAME_SAMPLES)
                while (playbackRunning && !stopped) {
                    val count = try { record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING) } catch (_: Exception) { -1 }
                    if (count <= 0) continue
                    val frame = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until count) frame.putShort(samples[i])
                    frame.flip()
                    try {
                        val active = socket
                        if (active?.isOpen == true) active.send(frame)
                    } catch (_: Exception) { }
                }
            }, "AtomosPlaybackAudio").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            stopInternalAudioCapture()
        }
    }

    private fun stopInternalAudioCapture() {
        playbackRunning = false
        try { playbackRecord?.stop() } catch (_: Exception) { }
        try { playbackThread?.join(350) } catch (_: Exception) { }
        try { playbackRecord?.release() } catch (_: Exception) { }
        playbackRecord = null
        playbackThread = null
        try { playbackProjection?.stop() } catch (_: Exception) { }
        playbackProjection = null
    }

    private fun collectStats() {
        val currentPeer = peer ?: return
        if (stopped) return
        try {
            currentPeer.getStats { report ->
                try {
                    var bytesSent = 0L
                    var fps = 0.0
                    var framesEncoded = 0L
                    var packetLoss = 0L
                    var rttSeconds = -1.0
                    report.statsMap.values.forEach { stat ->
                        val members = stat.members
                        when (stat.type) {
                            "outbound-rtp" -> {
                                bytesSent += (members["bytesSent"] as? Number)?.toLong() ?: 0L
                                fps = (members["framesPerSecond"] as? Number)?.toDouble() ?: fps
                                framesEncoded += (members["framesEncoded"] as? Number)?.toLong() ?: 0L
                            }
                            "remote-inbound-rtp" -> {
                                packetLoss += (members["packetsLost"] as? Number)?.toLong() ?: 0L
                                rttSeconds = (members["roundTripTime"] as? Number)?.toDouble() ?: rttSeconds
                            }
                            "candidate-pair" -> {
                                val selected = members["selected"] as? Boolean ?: false
                                if (selected) rttSeconds = (members["currentRoundTripTime"] as? Number)?.toDouble() ?: rttSeconds
                            }
                        }
                    }
                    val now = System.currentTimeMillis()
                    val elapsedSeconds = if (previousStatsAt > 0 && now > previousStatsAt) (now - previousStatsAt) / 1000.0 else 0.0
                    val bitrate = if (previousBytesSent >= 0 && elapsedSeconds > 0)
                        ((bytesSent - previousBytesSent).coerceAtLeast(0) * 8.0 / elapsedSeconds).toLong() else -1L
                    if (fps <= 0 && previousFramesEncoded >= 0 && elapsedSeconds > 0) fps = ((framesEncoded - previousFramesEncoded).coerceAtLeast(0) / elapsedSeconds)
                    previousBytesSent = bytesSent
                    previousFramesEncoded = framesEncoded
                    previousStatsAt = now
                    val payload = JSONObject().put("type", "diagnostics")
                        .put("source", "Android sender")
                        .put("state", currentPeer.connectionState().name.lowercase())
                        .put("bitrateBps", bitrate).put("fps", fps)
                        .put("packetsLost", packetLoss)
                        .put("rttMs", if (rttSeconds >= 0) (rttSeconds * 1000).toLong() else -1)
                        .put("at", now)
                    send(payload)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun createOffer() {
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        send(JSONObject().put("type", "offer").put("description",
                            JSONObject().put("type", "offer").put("sdp", description.description)))
                    }
                }, description)
            }
        }, MediaConstraints())
    }

    private fun send(message: JSONObject) {
        try { if (socket?.isOpen == true) socket?.send(message.toString()) } catch (_: Exception) {}
    }

    fun stop() {
        if (stopped) return
        stopped = true
        stopInternalAudioCapture()
        reconnectExecutor.shutdownNow()
        statsExecutor.shutdownNow()
        reconnectPending.set(false)
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose(); videoSource?.dispose(); microphoneTrack?.dispose(); microphoneSource?.dispose() } catch (_: Exception) {}
        try { capturer?.dispose(); textureHelper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose(); egl?.release() } catch (_: Exception) {}
    }
    companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_FRAME_SAMPLES = 480
        private const val AUDIO_FRAME_BYTES = AUDIO_FRAME_SAMPLES * 2
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
