package com.atomos.mirror

import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Small LAN-only signalling relay. Media never passes through this server. */
class SignalServer(port: Int = 8766) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    val pairingPin: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private val roles = ConcurrentHashMap<WebSocket, String>()

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
                    if (role == "sender" && data.optString("pin") != pairingPin) {
                        conn.send(error("Incorrect pairing PIN")); conn.close(1008, "Pairing rejected"); return
                    }
                    roles[conn] = role
                    conn.send(JSONObject().put("type", "joined").put("role", role).toString())
                    broadcast(JSONObject().put("type", "peer").put("role", role).put("state", "joined"), conn)
                }
                "offer", "answer", "ice", "hangup" -> {
                    if (!roles.containsKey(conn)) { conn.send(error("Pair before signalling")); return }
                    val clean = JSONObject(message)
                    broadcast(clean, conn)
                }
                else -> conn.send(error("Unknown message type"))
            }
        } catch (_: Exception) { conn.send(error("Invalid signalling message")) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val role = roles.remove(conn) ?: return
        broadcast(JSONObject().put("type", "peer").put("role", role).put("state", "left"), conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) { }
    override fun onStart() { }

    private fun broadcast(json: JSONObject, except: WebSocket) {
        val payload = json.toString()
        connections.filter { it != except && it.isOpen && roles.containsKey(it) }.forEach {
            try { it.send(payload) } catch (_: Exception) { }
        }
    }

    private fun error(message: String) = JSONObject().put("type", "error").put("message", message).toString()
}
