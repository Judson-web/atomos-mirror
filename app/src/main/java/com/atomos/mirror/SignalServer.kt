package com.atomos.mirror

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** LAN-only signalling relay and live peer registry. Media never passes through this server. */
class SignalServer(port: Int = 8766) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    data class Peer(val id: String, val name: String, val role: String, val connectedAt: Long)
    val pairingPin: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private val peers = ConcurrentHashMap<WebSocket, Peer>()

    fun devicesJson(): JSONArray {
        val result = JSONArray()
        peers.values.sortedBy { it.connectedAt }.forEach { peer ->
            result.put(JSONObject().put("id", peer.id).put("name", peer.name)
                .put("role", peer.role).put("state", "connected").put("connectedAt", peer.connectedAt))
        }
        return result
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conn.send(JSONObject().put("type", "challenge").put("message", "Send hello with role and pairing PIN").toString())
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val data = JSONObject(message)
            when (data.optString("type")) {
                "hello" -> {
                    val role = data.optString("role")
                    if (role != "sender" && role != "receiver") {
                        conn.send(error("Unsupported role")); return
                    }
                    if (data.optString("pin") != pairingPin) {
                        conn.send(error("Incorrect pairing PIN")); conn.close(1008, "Pairing rejected"); return
                    }
                    val peer = Peer(UUID.randomUUID().toString(), data.optString("name").ifBlank { "Unknown device" }.take(80), role, System.currentTimeMillis())
                    peers[conn] = peer
                    conn.send(JSONObject().put("type", "joined").put("role", role).put("deviceId", peer.id)
                        .put("peers", devicesJson()).toString())
                    broadcast(JSONObject().put("type", "peer").put("deviceId", peer.id).put("name", peer.name)
                        .put("role", role).put("state", "joined"), conn)
                }
                "offer", "answer", "ice", "hangup", "diagnostics" -> {
                    if (!peers.containsKey(conn)) { conn.send(error("Pair before signalling")); return }
                    broadcast(JSONObject(message), conn)
                }
                "heartbeat" -> {
                    val peer = peers[conn] ?: run { conn.send(error("Pair before heartbeat")); return }
                    conn.send(JSONObject().put("type", "heartbeat_ack").put("deviceId", peer.id).put("at", System.currentTimeMillis()).toString())
                }
                else -> conn.send(error("Unknown message type"))
            }
        } catch (_: Exception) { conn.send(error("Invalid signalling message")) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val peer = peers.remove(conn) ?: return
        broadcast(JSONObject().put("type", "peer").put("deviceId", peer.id).put("name", peer.name)
            .put("role", peer.role).put("state", "left"), conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) { }
    override fun onStart() { }

    private fun broadcast(json: JSONObject, except: WebSocket) {
        val payload = json.toString()
        connections.filter { it != except && it.isOpen && peers.containsKey(it) }.forEach {
            try { it.send(payload) } catch (_: Exception) { }
        }
    }

    private fun error(message: String) = JSONObject().put("type", "error").put("message", message).toString()
}
