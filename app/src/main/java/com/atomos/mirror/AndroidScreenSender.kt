package com.atomos.mirror

import android.content.Context
import android.content.Intent
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the Android display with MediaProjection and sends it directly to a paired LAN peer. */
class AndroidScreenSender(
    private val context: Context,
    private val projectionData: Intent,
    private val pairingPin: String,
    private val microphoneEnabled: Boolean = false
) {
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var microphoneSource: AudioSource? = null
    private var microphoneTrack: AudioTrack? = null
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
        peer!!.addTrack(videoTrack!!, listOf("atomos-screen-stream"))
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
        capturer!!.startCapture(1280, 720, 30)

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
                    .put("pin", pairingPin).put("name", "Android phone"))
            }
            override fun onMessage(message: String) {
                try {
                    val json = JSONObject(message)
                    when (json.optString("type")) {
                        "joined" -> createOffer()
                        "peer" -> if (json.optString("state") == "joined") createOffer()
                        "answer" -> peer?.setRemoteDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                while (pendingRemoteIce.isNotEmpty()) peer?.addIceCandidate(pendingRemoteIce.poll())
                            }
                        }, SessionDescription(SessionDescription.Type.ANSWER, json.getJSONObject("description").getString("sdp")))
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
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
