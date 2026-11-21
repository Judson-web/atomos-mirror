package com.atomos.mirror

import android.content.Context
import android.content.Intent
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.*
import java.net.URI

/** Captures the Android display with MediaProjection and sends it directly to a paired LAN peer. */
class AndroidScreenSender(
    private val context: Context,
    private val projectionData: Intent,
    private val pairingPin: String
) {
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var egl: EglBase? = null
    private var socket: WebSocketClient? = null
    private val pendingRemoteIce = java.util.concurrent.ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var stopped = false

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

        textureHelper = SurfaceTextureHelper.create("AtomosScreenCapture", egl!!.eglBaseContext)
        capturer = ScreenCapturerAndroid(projectionData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stop() }
        })
        capturer!!.initialize(textureHelper!!, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(1280, 720, 30)

        socket = object : WebSocketClient(URI("ws://127.0.0.1:8766")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                send(JSONObject().put("type", "hello").put("role", "sender")
                    .put("pin", pairingPin).put("name", "Android phone"))
            }
            override fun onMessage(message: String) {
                try {
                    val json = JSONObject(message)
                    when (json.optString("type")) {
                        "joined" -> createOffer()
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
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: Exception?) {}
        }
        socket!!.connect()
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
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose(); videoSource?.dispose() } catch (_: Exception) {}
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
