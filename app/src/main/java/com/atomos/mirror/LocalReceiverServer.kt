package com.atomos.mirror

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** Local-only HTTP dashboard hosted by the Android device. */
class LocalReceiverServer(
    context: Context,
    port: Int = 8765,
    private val peerSnapshot: () -> JSONArray = { JSONArray() },
    private val pairingChallenge: (String) -> JSONObject,
    private val approvedPairingToken: () -> String?
) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/api/status" -> jsonResponse(JSONObject().put("name", "Atomos Mirror").put("status", "online")
            .put("host", localAddress()).put("port", listeningPort).put("signalPort", 8766)
            .put("devices", peerSnapshot()).put("deviceCount", peerSnapshot().length())
            .put("transport", "local-webrtc").put("streamingReady", true))
        "/api/pairing/challenge" -> jsonResponse(pairingChallenge(localAddress()).put("httpPort", listeningPort))
        "/api/pairing/session" -> {
            val token = approvedPairingToken()
            if (token == null) jsonResponse(JSONObject().put("approved", false).put("message", "Scan the current QR in the Atomos app first"), Response.Status.FORBIDDEN)
            else jsonResponse(JSONObject().put("approved", true).put("token", token).put("signalPort", 8766))
        }
        "/api/pairing/qr.png" -> {
            val token = session.parameters["token"]?.firstOrNull().orEmpty()
            val expiresAt = session.parameters["expiresAt"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val payload = JSONObject().put("type", "atomos-pair").put("version", 1)
                .put("host", localAddress()).put("signalPort", 8766)
                .put("httpPort", listeningPort).put("token", token)
                .put("expiresAt", expiresAt).toString()
            val bytes = createQrPng(payload)
            newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
        "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DASHBOARD)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
        }

    private fun createQrPng(payload: String): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        for (y in 0 until 480) for (x in 0 until 480)
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    fun localAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<NetworkInterface>()
            while (interfaces.hasMoreElements()) candidates.add(interfaces.nextElement())
            val ordered = candidates.filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    !name.startsWith("tun") && !name.startsWith("ppp") &&
                    !name.startsWith("wg") && !name.contains("tailscale")
            }.sortedBy { network ->
                val name = network.name.lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }
            }
            for (network in ordered) {
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && address.isSiteLocalAddress &&
                        !address.isLoopbackAddress && !address.isLinkLocalAddress)
                        return address.hostAddress ?: "Unavailable"
                }
            }
        } catch (_: Exception) { }
        return "Connect to Wi-Fi"
    }

    companion object {
        private val DASHBOARD = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#090d18"><title>Atomos Mirror</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,sans-serif;background:#090d18;color:#eef3ff}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 80% -10%,#1d3760,transparent 42%),#090d18}
main{max-width:1050px;margin:auto;padding:42px 24px}.top{display:flex;align-items:center;justify-content:space-between;gap:16px}.brand{display:flex;align-items:center;gap:13px}.logo{width:46px;height:46px;border-radius:15px;background:linear-gradient(145deg,#75a7ff,#6856db);display:grid;place-items:center;font-weight:900;font-size:21px}h1{font-size:22px;margin:0}small,.muted{color:#91a0bb}.pill{border:1px solid #275d50;color:#79e3ba;background:#102b27;padding:8px 12px;border-radius:99px;font-size:12px}
.hero{margin:64px 0 34px}.eyebrow{color:#8eafff;text-transform:uppercase;letter-spacing:2px;font-size:11px;font-weight:700}.hero h2{font-size:clamp(34px,6vw,58px);letter-spacing:-2px;line-height:1.05;margin:14px 0}.hero p{max-width:600px;color:#9eabc2;line-height:1.7}.grid{display:grid;grid-template-columns:1.3fr .7fr;gap:18px}.panel{background:#111a2b;border:1px solid #25324a;border-radius:22px;padding:24px;box-shadow:0 20px 70px #0003}.panel h3{margin:0 0 18px;font-size:16px}.empty{border:1px dashed #34445f;border-radius:16px;padding:34px 18px;text-align:center;color:#9eabc2}.glyph{font-size:34px;margin-bottom:12px}.address{font:600 19px ui-monospace,monospace;overflow-wrap:anywhere;color:#c8d8ff;margin:12px 0}.copy{border:0;border-radius:12px;padding:11px 15px;background:#456eea;color:white;font-weight:700;cursor:pointer}.stat{display:flex;justify-content:space-between;padding:13px 0;border-bottom:1px solid #26334a;color:#aab6ca}.stat b{color:#f0f4ff}footer{margin-top:28px;color:#66758f;font-size:12px;line-height:1.7}@media(max-width:700px){main{padding:24px 16px}.grid{grid-template-columns:1fr}.hero{margin:48px 0 26px}}
</style></head><body><main><header class="top"><div class="brand"><div class="logo">A</div><div><h1>Atomos Mirror</h1><small>Local receiver</small></div></div><span class="pill">● LOCAL SERVER</span></header>
<section class="hero"><div class="eyebrow">Your devices, one screen</div><h2>Connect. Mirror.<br>Stay in control.</h2><p>This receiver is running directly from your Android device. Connect another device to the same Wi-Fi network and use Atomos Mirror to discover this receiver.</p></section>
<section class="grid"><article class="panel"><h3>Connection list <span class="muted" id="count">· 0 devices</span></h3><div id="device-list" class="empty"><div class="glyph">⌁</div><b>No devices connected</b><p class="muted">Open the Windows companion and press Find. This receiver is advertised on your local Wi-Fi network.</p></div></article>
<aside class="panel"><h3>Receiver details</h3><div class="stat"><span>Network</span><b>Local Wi-Fi</b></div><div class="stat"><span>Server</span><b id="server">Starting…</b></div><div class="stat"><span>Media stream</span><b>Not connected</b></div><div class="address" id="address">Loading address…</div><button class="copy" onclick="navigator.clipboard?.writeText(location.href)">Copy receiver address</button><div style="margin-top:22px;padding-top:18px;border-top:1px solid #26334a"><h3 style="margin-bottom:8px">Secure app pairing</h3><p class="muted" style="font-size:12px;line-height:1.6">Generate a short-lived QR code, then scan it from the Atomos Mirror Android app to approve this device.</p><img id="pair-qr" alt="Pairing QR code" style="display:none;width:min(100%,260px);background:white;padding:10px;border-radius:14px;margin:14px auto"><button class="copy" id="generate-pair-qr" type="button">Generate pairing QR</button><p id="pair-qr-state" class="muted" style="font-size:11px;margin-top:8px">QR expires after three minutes.</p></div></aside></section>
<footer>Private local-network dashboard · Served by your Android device · No cloud account required.<br>Pairing requires scanning an expiring QR code in the Atomos Mirror Android app. Media travels directly between devices over WebRTC.</footer></main>
<script>async function refresh(){try{const r=await fetch('/api/status',{cache:'no-store'});const s=await r.json();document.querySelector('#server').textContent=s.status==='online'?'Online':'Offline';document.querySelector('#address').textContent='http://'+s.host+':'+s.port;document.querySelector('#count').textContent='· '+s.devices.length+' devices';const list=document.querySelector('#device-list');if(s.devices.length){list.className='';list.innerHTML=s.devices.map(d=>'<div class=\"stat\"><span>'+String(d.name).replace(/[&<>\"']/g,'')+' · '+d.role+'</span><b>● Connected</b></div>').join('')}else{list.className='empty';list.innerHTML='<div class=\"glyph\">⌁</div><b>No devices connected</b><p class=\"muted\">Open the Windows companion and press Find. This receiver is advertised on your local Wi-Fi network.</p>'}}catch(e){document.querySelector('#server').textContent='Unavailable'}}refresh();setInterval(refresh,3000);document.querySelector('#generate-pair-qr').addEventListener('click',()=>{const image=document.querySelector('#pair-qr');image.src='/api/pairing/qr.png?'+Date.now();image.style.display='block';document.querySelector('#pair-qr-state').textContent='Scan this QR in Atomos Mirror on your phone. It expires in three minutes.'});</script></body></html>
        <script>
let pc=null,ws=null,iceQueue=[],browserRetry=0,browserRetryTimer=null;
const video=document.createElement('video');
video.autoplay=true;video.playsInline=true;video.controls=true;
video.style.cssText='display:block;width:100%;max-height:65vh;background:#05080f;border-radius:14px;margin:18px 0;object-fit:contain';
const main=document.querySelector('main');
const panel=document.createElement('section');panel.style.cssText='background:#111a2b;border:1px solid #25324a;border-radius:20px;padding:20px;margin-top:18px';
panel.innerHTML='<h3>Live desktop stream</h3><div style="display:flex;gap:8px;flex-wrap:wrap;margin:12px 0"><span style="color:#9eabc2;font-size:12px">Scan the QR above with the Android app, then connect this browser receiver.</span><button id="am-connect" class="copy" type="button">Connect receiver</button></div><p id="am-state" style="color:#9eabc2">Waiting for Windows companion…</p><div id="am-metrics" style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:12px 0"><div class="stat"><span>Bitrate</span><b id="am-bitrate">—</b></div><div class="stat"><span>FPS</span><b id="am-fps">—</b></div><div class="stat"><span>Loss</span><b id="am-loss">—</b></div><div class="stat"><span>RTT</span><b id="am-rtt">—</b></div></div><p id="am-remote" class="muted" style="font-size:11px">WebRTC diagnostics will appear when streaming starts.</p>';
panel.appendChild(video);main.appendChild(panel);
let amPreviousBytes=0,amPreviousFrames=0,amPreviousAt=0;
function amMetric(id,value){const el=document.querySelector('#'+id);if(el)el.textContent=value}
async function amUpdateStats(){
 if(!pc){amMetric('am-bitrate','—');amMetric('am-fps','—');amMetric('am-loss','—');amMetric('am-rtt','—');return}
 try{
  const report=await pc.getStats();let bytes=0,fps=0,frames=0,lost=0,received=0,rtt=null;
  report.forEach(s=>{
   if(s.type==='inbound-rtp'&&(s.kind==='video'||s.mediaType==='video')){bytes+=s.bytesReceived||0;fps=s.framesPerSecond||fps;frames+=s.framesDecoded||0;lost+=s.packetsLost||0;received+=s.packetsReceived||0}
   if(s.type==='outbound-rtp'&&(s.kind==='video'||s.mediaType==='video')){bytes+=s.bytesSent||0;fps=s.framesPerSecond||fps;frames+=s.framesEncoded||0}
   if(s.type==='candidate-pair'&&s.state==='succeeded'&&(s.nominated||s.selected))rtt=s.currentRoundTripTime??rtt
  });
  const now=performance.now(),seconds=amPreviousAt&&now>amPreviousAt?(now-amPreviousAt)/1000:0,rate=seconds?Math.max(0,(bytes-amPreviousBytes)*8/seconds):null,measuredFps=fps||(seconds?Math.max(0,(frames-amPreviousFrames)/seconds):0);
  amPreviousBytes=bytes;amPreviousFrames=frames;amPreviousAt=now;
  amMetric('am-bitrate',rate==null?'Measuring…':rate>=1e6?(rate/1e6).toFixed(2)+' Mbps':Math.round(rate/1000)+' kbps');
  amMetric('am-fps',measuredFps?Math.round(measuredFps)+' FPS':'—');
  amMetric('am-loss',received+lost?((lost/(received+lost))*100).toFixed(2)+'%':'—');
  amMetric('am-rtt',rtt==null?'—':Math.round(rtt*1000)+' ms');
 }catch(e){amMetric('am-remote','Stats unavailable: '+e.message)}
}
setInterval(amUpdateStats,1000);
async function atomosReceiver(){
 const s=await (await fetch('/api/status',{cache:'no-store'})).json();
 const pairingResponse=await fetch('/api/pairing/session',{cache:'no-store'});
 const pairing=await pairingResponse.json();
 const pairingToken=pairing.token;
 if(!pairingResponse.ok||!pairingToken){document.querySelector('#am-state').textContent='Scan the current pairing QR in the Atomos Android app first.';return}
 if(ws && ws.readyState===WebSocket.OPEN)ws.close();
 ws=new WebSocket('ws://'+s.host+':'+s.signalPort);
 const activeSocket=ws;
 const send=m=>{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(m))};
 ws.onopen=()=>{browserRetry=0;send({type:'hello',role:'receiver',pairingToken,name:'Android browser receiver'})};
 ws.onmessage=async e=>{
  const m=JSON.parse(e.data);
  if(m.type==='offer'){
   if(pc)pc.close();pc=new RTCPeerConnection({iceServers:[]});
   pc.ontrack=ev=>{video.srcObject=ev.streams[0];video.play().catch(()=>{});document.querySelector('#am-state').textContent='Receiving desktop stream'};
   pc.onicecandidate=ev=>{if(ev.candidate)send({type:'ice',candidate:ev.candidate})};
   pc.onconnectionstatechange=()=>{document.querySelector('#am-state').textContent='WebRTC: '+pc.connectionState};
   await pc.setRemoteDescription(m.description);for(const c of iceQueue)await pc.addIceCandidate(c);iceQueue=[];
   const answer=await pc.createAnswer();await pc.setLocalDescription(answer);send({type:'answer',description:pc.localDescription});
  }else if(m.type==='ice'&&m.candidate&&pc){const c=new RTCIceCandidate(m.candidate);if(pc.remoteDescription)await pc.addIceCandidate(c);else iceQueue.push(c)}
  else if(m.type==='hangup'){if(pc)pc.close();pc=null;video.srcObject=null;document.querySelector('#am-state').textContent='Desktop sharing stopped'}
  else if(m.type==='diagnostics')document.querySelector('#am-remote').textContent='Android sender · '+m.state+' · '+(m.bitrateBps>=0?(m.bitrateBps/1e6).toFixed(2)+' Mbps':'measuring')+' · '+(m.fps?Math.round(m.fps)+' FPS':'FPS unavailable')+' · '+(m.rttMs>=0?m.rttMs+' ms RTT':'RTT unavailable');
  else if(m.type==='error'){document.querySelector('#am-state').textContent=m.message;browserRetry=8;clearTimeout(browserRetryTimer)}
 };
 ws.onclose=()=>{if(ws!==activeSocket)return;if(pc){pc.close();pc=null}video.srcObject=null;if(browserRetry<8){document.querySelector('#am-state').textContent='Signalling disconnected · retrying';clearTimeout(browserRetryTimer);const wait=Math.min(1000*Math.pow(2,browserRetry),20000);browserRetry++;browserRetryTimer=setTimeout(()=>atomosReceiver().catch(()=>{}),wait)}else{document.querySelector('#am-state').textContent='Disconnected. Scan a fresh QR and reconnect.'}};
}
document.querySelector('#am-connect').addEventListener('click',()=>{browserRetry=0;atomosReceiver().catch(e=>document.querySelector('#am-state').textContent='Receiver error: '+e.message)});
        </script>
        """.trimIndent()
    }
}
