package com.atomos.mirror

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * LAN-only signalling coordinator. A short-lived QR challenge must be approved
 * by the Android app before either peer can join. Media never passes through here.
 */
class SignalServer(port: Int = 8766) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    data class Peer(val id: String, val name: String, val role: String, val connectedAt: Long)
    private val peers = ConcurrentHashMap<WebSocket, Peer>()
    private data class AuthWindow(var startedAt: Long, var failures: Int, var blockedUntil: Long = 0L)
    private val authWindows = ConcurrentHashMap<String, AuthWindow>()
    private val random = SecureRandom()
    @Volatile private var challengeToken: String = ""
    @Volatile private var challengeExpiresAt: Long = 0L
    @Volatile private var challengeApproved: Boolean = false

    init { setConnectionLostTimeout(20) }

    @Synchronized
    fun createPairingChallenge(host: String): JSONObject {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        challengeToken = bytes.joinToString("") { "%02x".format(it) }
        challengeExpiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS
        challengeApproved = false
        return JSONObject()
            .put("type", "atomos-pair")
            .put("version", 1)
            .put("host", host)
            .put("signalPort", listeningPort)
            .put("token", challengeToken)
            .put("expiresAt", challengeExpiresAt)
    }

    @Synchronized
    fun approvePairingToken(token: String): Boolean {
        if (token.isBlank() || System.currentTimeMillis() > challengeExpiresAt) return false
        val valid = MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), challengeToken.toByteArray(Charsets.UTF_8))
        if (valid) challengeApproved = true
        return valid
    }

    @Synchronized
    fun approvedPairingToken(): String? =
        if (challengeApproved && System.currentTimeMillis() <= challengeExpiresAt) challengeToken else null

    fun devicesJson(): JSONArray {
        val result = JSONArray()
        peers.values.sortedBy { it.connectedAt }.forEach { peer ->
            result.put(JSONObject().put("id", peer.id).put("name", peer.name)
                .put("role", peer.role).put("state", "connected").put("connectedAt", peer.connectedAt))
        }
        return result
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conn.send(JSONObject().put("type", "challenge")
            .put("message", "Scan the pairing QR in the Atomos Mirror Android app first").toString())
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > MAX_SIGNAL_BYTES) {
            conn.close(1009, "Message too large")
            return
        }
        try {
            val data = JSONObject(message)
            when (data.optString("type")) {
                "hello" -> authenticate(conn, data)
                "offer", "answer", "ice", "hangup", "diagnostics" -> relay(conn, message, data)
                "heartbeat" -> {
                    val peer = peers[conn] ?: run { conn.send(error("Pair before heartbeat")); return }
                    conn.send(JSONObject().put("type", "heartbeat_ack").put("deviceId", peer.id)
                        .put("at", System.currentTimeMillis()).toString())
                }
                else -> conn.send(error("Unknown message type"))
            }
        } catch (_: Exception) { conn.send(error("Invalid signalling message")) }
    }

    private fun authenticate(conn: WebSocket, data: JSONObject) {
        if (peers.containsKey(conn)) {
            conn.send(error("This connection is already paired"))
            return
        }
        val role = data.optString("role")
        if (role != "sender" && role != "receiver") {
            conn.send(error("Unsupported role"))
            conn.close(1008, "Unsupported role")
            return
        }
        val remoteIp = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val window = authWindows[remoteIp]
        if (window != null && window.blockedUntil > now) {
            conn.send(error("Too many pairing attempts. Wait before trying again."))
            conn.close(1008, "Pairing temporarily rate limited")
            return
        }
        val supplied = data.optString("pairingToken")
        val approved = synchronized(this) {
            challengeApproved && now <= challengeExpiresAt &&
                MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), challengeToken.toByteArray(Charsets.UTF_8))
        }
        if (!approved) {
            authWindows.compute(remoteIp) { _, current ->
                val active = if (current == null || now - current.startedAt > AUTH_WINDOW_MS)
                    AuthWindow(now, 0) else current
                active.failures += 1
                if (active.failures >= MAX_AUTH_FAILURES) active.blockedUntil = now + AUTH_LOCKOUT_MS
                active
            }
            conn.send(error("Pairing not approved or QR expired. Scan the current QR in the Android app."))
            conn.close(1008, "Pairing rejected")
            return
        }

        authWindows.remove(remoteIp)
        synchronized(peers) {
            if (peers.size >= MAX_PEERS || peers.values.any { it.role == role }) {
                conn.send(error("A $role is already connected. Disconnect it before pairing another device."))
                conn.close(1008, "Session role already occupied")
                return
            }
            val peer = Peer(
                UUID.randomUUID().toString(),
                data.optString("name").ifBlank { "Unknown device" }.take(80),
                role,
                System.currentTimeMillis()
            )
            peers[conn] = peer
            conn.send(JSONObject().put("type", "joined").put("role", role)
                .put("deviceId", peer.id).put("peers", devicesJson()).toString())
            broadcast(JSONObject().put("type", "peer").put("deviceId", peer.id)
                .put("name", peer.name).put("role", role).put("state", "joined"), conn)
        }
    }

    private fun relay(conn: WebSocket, message: String, data: JSONObject) {
        val source = peers[conn]
        if (source == null) {
            conn.send(error("Pair before signalling"))
            return
        }
        val type = data.optString("type")
        val allowedForRole = when (type) {
            "offer", "diagnostics" -> source.role == "sender"
            "answer" -> source.role == "receiver"
            else -> true
        }
        if (!allowedForRole) {
            conn.send(error("Message type is not valid for this peer role"))
            return
        }
        val targets = peers.entries.filter { (socket, peer) ->
            socket != conn && socket.isOpen && peer.role != source.role
        }
        if (targets.isEmpty()) return
        val payload = JSONObject(message)
        targets.forEach { (socket, _) -> try { socket.send(payload.toString()) } catch (_: Exception) { } }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val peer = peers.remove(conn) ?: return
        broadcast(JSONObject().put("type", "peer").put("deviceId", peer.id)
            .put("name", peer.name).put("role", peer.role).put("state", "left"), conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}
    override fun onStart() {}

    private fun broadcast(json: JSONObject, except: WebSocket) {
        val payload = json.toString()
        val source = peers[except]
        peers.entries.filter { (socket, peer) ->
            socket != except && socket.isOpen && (source == null || peer.role != source.role)
        }.forEach { (socket, _) -> try { socket.send(payload) } catch (_: Exception) { } }
    }

    private fun error(message: String) = JSONObject().put("type", "error").put("message", message).toString()

    companion object {
        private const val MAX_PEERS = 2
        private const val MAX_SIGNAL_BYTES = 1_000_000
        private const val MAX_AUTH_FAILURES = 5
        private const val AUTH_WINDOW_MS = 60_000L
        private const val AUTH_LOCKOUT_MS = 30_000L
        private const val CHALLENGE_TTL_MS = 180_000L
    }
}
