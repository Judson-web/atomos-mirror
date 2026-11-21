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
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#090d18">
<title>Atomos Mirror · Laptop Receiver</title>
<style>
:root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;background:#080c16;color:#edf3ff}
*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 78% -10%,#203a67 0,transparent 42%),#080c16}
main{max-width:1180px;margin:auto;padding:28px 24px 50px}.top{display:flex;align-items:center;justify-content:space-between;gap:16px}
.brand{display:flex;align-items:center;gap:12px}.logo{width:44px;height:44px;border-radius:14px;background:linear-gradient(145deg,#78adff,#6657dc);display:grid;place-items:center;font-weight:900;font-size:21px}
h1{font-size:19px;margin:0}.sub,.muted{color:#91a0bb}.sub{font-size:12px}.pill{border:1px solid #2d594e;color:#7be0b6;background:#102b25;padding:8px 12px;border-radius:99px;font-size:11px;letter-spacing:.5px}
.hero{padding:48px 0 28px}.eyebrow{color:#8eafff;text-transform:uppercase;letter-spacing:2px;font-size:10px;font-weight:800}.hero h2{font-size:clamp(34px,5vw,58px);letter-spacing:-2.5px;line-height:1.02;margin:12px 0}.hero p{max-width:660px;color:#9eabc2;line-height:1.7;margin:0}
.layout{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(290px,.75fr);gap:18px;align-items:start}.stack{display:grid;gap:18px}.panel{background:#111a2a;border:1px solid #25334b;border-radius:20px;padding:20px;box-shadow:0 18px 55px #0002}.panel h3{font-size:15px;margin:0 0 14px}.stage{position:relative;background:#03060c;border:1px solid #26334a;border-radius:15px;overflow:hidden;min-height:260px;display:grid;place-items:center}
video{display:block;width:100%;max-height:72vh;object-fit:contain;background:#03060c}.stage-empty{text-align:center;padding:42px 18px;color:#8d9bb5}.stage-empty .glyph{font-size:42px;margin-bottom:12px;color:#7899e8}
.row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.between{justify-content:space-between}.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}
button,select{border:1px solid #334462;border-radius:11px;padding:10px 13px;background:#1b2940;color:#edf3ff;font-weight:650;cursor:pointer}
button.primary{background:#4b70e8;border-color:#4b70e8}button:disabled{opacity:.45;cursor:not-allowed}
.statgrid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-top:14px}.stat{background:#0d1524;border:1px solid #24324a;border-radius:12px;padding:11px}.stat span{display:block;color:#8494af;font-size:10px}.stat b{display:block;margin-top:5px;font-size:16px;font-variant-numeric:tabular-nums}
.qrwrap{display:grid;place-items:center;background:#fff;border-radius:16px;padding:12px;min-height:230px;margin:14px auto;max-width:250px}.qrwrap img{width:100%;height:auto;display:none}.qr-placeholder{color:#25334b;text-align:center;font-size:12px;padding:30px}
.address{font:600 15px ui-monospace,monospace;overflow-wrap:anywhere;color:#c9d8ff;margin:10px 0}.line{height:1px;background:#26344c;margin:16px 0}
label{font-size:12px;color:#aab8cf}.hint{font-size:11px;color:#8191ad;line-height:1.55}.field{display:grid;gap:8px;margin:13px 0}.field select{width:100%}.range{width:100%;accent-color:#759cff}
.status{font-size:12px;color:#aab8cf;line-height:1.5}.status.good{color:#78e0b5}.status.bad{color:#ff9c9c}.footer{margin-top:24px;color:#66758f;font-size:11px;line-height:1.7}
@media(max-width:820px){main{padding:20px 14px 36px}.layout{grid-template-columns:1fr}.hero{padding:36px 0 24px}.statgrid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style></head>
<body><main>
<header class="top"><div class="brand"><div class="logo">A</div><div><h1>Atomos Mirror</h1><div class="sub">Laptop receiver · local network</div></div></div><span class="pill" id="server-pill">● ANDROID RECEIVER</span></header>
<section class="hero"><div class="eyebrow">Private screen streaming</div><h2>Your phone.<br>Your laptop. One screen.</h2><p>Open this page on your laptop, pair it with the Atomos Mirror app using the QR code, then start screen sharing on your phone. Video travels directly over your local network.</p></section>
<div class="layout">
<section class="stack">
<article class="panel"><div class="row between"><h3>Live phone screen</h3><span class="status" id="stream-state">Not connected</span></div>
<div class="stage" id="stage"><div class="stage-empty" id="empty"><div class="glyph">▣</div><b>Waiting for your phone</b><p class="hint">Pair this laptop, then tap Share this phone screen in the Android app.</p></div><video id="screen" autoplay playsinline controls style="display:none"></video></div>
<div class="actions"><button id="fullscreen" type="button">Fullscreen</button><button id="fit" type="button">Fit: contain</button><button id="disconnect" type="button" disabled>Disconnect</button></div>
<div class="statgrid"><div class="stat"><span>Video bitrate</span><b id="bitrate">—</b></div><div class="stat"><span>Frame rate</span><b id="fps">—</b></div><div class="stat"><span>Packet loss</span><b id="loss">—</b></div><div class="stat"><span>Round trip</span><b id="rtt">—</b></div></div>
<div class="row between" style="margin-top:14px"><span class="hint" id="video-info">Video diagnostics appear when streaming starts.</span><span class="hint" id="audio-state">Internal audio: waiting</span></div>
</article>
<article class="panel"><h3>Stream controls</h3>
<div class="field"><label for="quality">Video quality profile</label><select id="quality"><option value="smooth">Smooth · 720p / 30 FPS · lower bandwidth</option><option value="balanced" selected>Balanced · 1080p / 30 FPS</option><option value="high">High · up to 1080p / 60 FPS</option></select><div class="hint">The phone adjusts capture size and encoder bitrate when the profile is applied.</div></div>
<div class="field"><label for="volume">Internal audio volume <span id="volume-value">80%</span></label><input id="volume" class="range" type="range" min="0" max="100" value="80"></div>
<div class="row"><button id="mute" type="button">Mute audio</button><button id="apply-quality" class="primary" type="button" disabled>Apply video profile</button></div>
<p class="hint">Internal audio is a separate low-latency local stream. Android apps can block playback capture, and DRM/protected audio is never captured. Browser autoplay rules may require one click before audio plays.</p>
</article>
</section>
<aside class="stack">
<article class="panel"><h3>Pair this laptop</h3><p class="hint">1. Keep your phone and laptop on the same Wi-Fi network.<br>2. Generate the QR below.<br>3. Open Pair in the Android app and scan it.<br>4. Return here and connect.</p>
<div class="qrwrap"><img id="pair-qr" alt="One-time Atomos pairing QR"><div class="qr-placeholder" id="qr-placeholder">Your one-time pairing QR appears here.</div></div>
<div class="row between"><span class="status" id="qr-state">QR not generated</span><span class="hint" id="qr-countdown"></span></div>
<div class="actions"><button id="generate" class="primary" type="button">Generate pairing QR</button><button id="connect" type="button" disabled>Connect phone</button></div>
<p class="hint" id="pair-hint">The QR expires after three minutes and must be scanned in the Android app.</p>
</article>
<article class="panel"><h3>Connection details</h3><div class="stat"><span>Receiver server</span><b id="server-state">Checking…</b></div><div class="stat"><span>Paired devices</span><b id="device-count">0</b></div><div class="stat"><span>Transport</span><b>WebRTC · LAN</b></div><div class="address" id="receiver-address">Loading local address…</div><button id="copy-address" type="button">Copy receiver address</button><p class="hint">Open this address with <b>http://</b>, not https://. If Firefox upgrades it, use the exact HTTP address and allow HTTP for this local network address in HTTPS-Only mode.</p><div class="line"></div><div class="status" id="connection-help">No cloud account or relay server is used. Your router must allow devices on the same network to communicate.</div></article>
</aside></div>
<div class="footer">Atomos Mirror 1.2.1 · Android-to-laptop only · No Windows companion required · Use a trusted private Wi-Fi network. Pairing uses a random one-time token; local HTTP/WebSocket traffic is not TLS encrypted.</div>
</main>
<script>
let ws=null,pc=null,iceQueue=[],challengeExpiry=0,challengeTimer=null,statsTimer=null,audioContext=null,audioGain=null,nextAudioTime=0,muted=false,previousBytes=0,previousFrames=0,previousAt=0;
const el=id=>document.getElementById(id),video=el('screen');
function setState(text,kind){el('stream-state').textContent=text;el('stream-state').className='status '+(kind||'')}
function metric(id,value){el(id).textContent=value}
async function refreshStatus(){try{const r=await fetch('/api/status',{cache:'no-store'});const s=await r.json();el('server-state').textContent='Online';el('receiver-address').textContent='http://'+s.host+':'+s.port;el('device-count').textContent=String(s.deviceCount||0);el('server-pill').textContent='● ANDROID RECEIVER ONLINE'}catch(e){el('server-state').textContent='Unavailable';el('server-pill').textContent='● RECEIVER OFFLINE'}}
async function generateQr(){
 try{
  const response=await fetch('/api/pairing/challenge',{cache:'no-store'});if(!response.ok)throw new Error('Could not create pairing challenge');
  const challenge=await response.json();
  const image=el('pair-qr');image.src='/api/pairing/qr.png?token='+encodeURIComponent(challenge.token)+'&expiresAt='+encodeURIComponent(challenge.expiresAt)+'&v='+Date.now();image.style.display='block';el('qr-placeholder').style.display='none';
  challengeExpiry=challenge.expiresAt;el('qr-state').textContent='Waiting for phone approval';el('connect').disabled=true;
  clearInterval(challengeTimer);challengeTimer=setInterval(updateChallenge,1000);updateChallenge();
 }catch(e){el('qr-state').textContent=e.message}
}
async function updateChallenge(){
 const seconds=Math.max(0,Math.ceil((challengeExpiry-Date.now())/1000));
 el('qr-countdown').textContent=seconds?Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0')+' left':'Expired';
 if(!seconds){el('qr-state').textContent='QR expired. Generate a new one.';el('connect').disabled=true;clearInterval(challengeTimer);return}
 try{const r=await fetch('/api/pairing/session',{cache:'no-store'});if(r.ok){el('qr-state').textContent='Phone approved this laptop';el('connect').disabled=false;el('pair-hint').textContent='Approval received. Connect the laptop browser to begin streaming.'}}catch(e){}
}
async function ensureAudio(){
 if(!audioContext){audioContext=new (window.AudioContext||window.webkitAudioContext)({sampleRate:48000});audioGain=audioContext.createGain();audioGain.gain.value=Number(el('volume').value)/100;audioGain.connect(audioContext.destination)}
 if(audioContext.state==='suspended')await audioContext.resume();
}
async function playPcm(buffer){
 try{
  await ensureAudio();const input=new Int16Array(buffer);if(!input.length)return;
  const audio=audioContext.createBuffer(1,input.length,48000),channel=audio.getChannelData(0);
  for(let i=0;i<input.length;i++)channel[i]=input[i]/32768;
  const source=audioContext.createBufferSource();source.buffer=audio;source.connect(audioGain);
  const now=audioContext.currentTime;if(nextAudioTime<now+0.025)nextAudioTime=now+0.04;
  source.start(nextAudioTime);nextAudioTime+=audio.duration;el('audio-state').textContent='Internal audio: receiving';
 }catch(e){el('audio-state').textContent='Audio playback unavailable'}
}
async function connectReceiver(){
 try{
  await ensureAudio();
  const approval=await fetch('/api/pairing/session',{cache:'no-store'});const pairing=await approval.json();
  if(!approval.ok||!pairing.token)throw new Error('Scan the current QR with the Android app first.');
  const status=await (await fetch('/api/status',{cache:'no-store'})).json();
  if(ws)ws.close();ws=new WebSocket('ws://'+status.host+':'+status.signalPort);ws.binaryType='arraybuffer';
  const active=ws;el('connect').disabled=true;setState('Connecting to phone…');el('disconnect').disabled=false;
  ws.onopen=()=>{if(ws!==active)return;ws.send(JSON.stringify({type:'hello',role:'receiver',pairingToken:pairing.token,name:'Laptop browser'}));};
  ws.onmessage=async event=>{
   if(typeof event.data!=='string'){playPcm(event.data);return}
   const message=JSON.parse(event.data);
   if(message.type==='joined'){setState('Paired · waiting for screen share','good');el('apply-quality').disabled=false;await ensureAudio()}
   else if(message.type==='offer'){
    if(pc)pc.close();pc=new RTCPeerConnection({iceServers:[]});
    pc.ontrack=event=>{video.srcObject=event.streams[0];video.style.display='block';el('empty').style.display='none';video.play().catch(()=>{});setState('Receiving phone screen','good')};
    pc.onicecandidate=event=>{if(event.candidate&&ws===active&&ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'ice',candidate:event.candidate}))};
    pc.onconnectionstatechange=()=>setState('Video: '+pc.connectionState,pc.connectionState==='connected'?'good':'');
    await pc.setRemoteDescription(message.description);for(const candidate of iceQueue)await pc.addIceCandidate(candidate);iceQueue=[];
    const answer=await pc.createAnswer();await pc.setLocalDescription(answer);ws.send(JSON.stringify({type:'answer',description:pc.localDescription}));
   }else if(message.type==='ice'&&message.candidate&&pc){const candidate=new RTCIceCandidate(message.candidate);if(pc.remoteDescription)await pc.addIceCandidate(candidate);else iceQueue.push(candidate)}
   else if(message.type==='peer'&&message.state==='left'){setState('Phone disconnected');}
   else if(message.type==='error'){setState(message.message,'bad');}
   else if(message.type==='diagnostics'){el('video-info').textContent='Phone encoder · '+message.state+' · '+(message.fps?Math.round(message.fps)+' FPS':'FPS pending')}
  };
  ws.onclose=()=>{if(ws!==active)return;setState('Disconnected');el('connect').disabled=false;el('disconnect').disabled=true;if(pc){pc.close();pc=null}video.srcObject=null;video.style.display='none';el('empty').style.display='block'};
 }catch(e){setState(e.message,'bad');el('connect').disabled=false}
}
function disconnect(){if(ws){try{ws.close()}catch(e){}ws=null}if(pc){pc.close();pc=null}video.srcObject=null;video.style.display='none';el('empty').style.display='block';el('disconnect').disabled=true;el('connect').disabled=false;setState('Disconnected')}
async function updateStats(){
 if(!pc)return;try{const report=await pc.getStats();let bytes=0,fps=0,frames=0,lost=0,received=0,rtt=null;
 report.forEach(s=>{if(s.type==='inbound-rtp'&&(s.kind==='video'||s.mediaType==='video')){bytes+=s.bytesReceived||0;fps=s.framesPerSecond||fps;frames+=s.framesDecoded||0;lost+=s.packetsLost||0;received+=s.packetsReceived||0}if(s.type==='candidate-pair'&&s.state==='succeeded'&&(s.nominated||s.selected))rtt=s.currentRoundTripTime??rtt});
 const now=performance.now(),seconds=previousAt?(now-previousAt)/1000:0,rate=seconds?(bytes-previousBytes)*8/seconds:0;previousBytes=bytes;previousAt=now;previousFrames=frames;
 metric('bitrate',rate>1e6?(rate/1e6).toFixed(2)+' Mbps':Math.round(rate/1000)+' kbps');metric('fps',fps?Math.round(fps)+' FPS':'—');metric('loss',received+lost?((lost/(received+lost))*100).toFixed(2)+'%':'—');metric('rtt',rtt==null?'—':Math.round(rtt*1000)+' ms');
 }catch(e){}
}
el('generate').addEventListener('click',generateQr);el('connect').addEventListener('click',connectReceiver);el('disconnect').addEventListener('click',disconnect);
el('fullscreen').addEventListener('click',()=>{if(video.requestFullscreen)video.requestFullscreen()});
el('fit').addEventListener('click',()=>{video.style.objectFit=video.style.objectFit==='cover'?'contain':'cover';el('fit').textContent='Fit: '+video.style.objectFit});
el('volume').addEventListener('input',async()=>{el('volume-value').textContent=el('volume').value+'%';await ensureAudio();audioGain.gain.value=muted?0:Number(el('volume').value)/100});
el('mute').addEventListener('click',()=>{muted=!muted;el('mute').textContent=muted?'Unmute audio':'Mute audio';if(audioGain)audioGain.gain.value=muted?0:Number(el('volume').value)/100});
el('apply-quality').addEventListener('click',()=>{if(ws&&ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'settings',profile:el('quality').value}));});
el('copy-address').addEventListener('click',()=>navigator.clipboard?.writeText(el('receiver-address').textContent));
refreshStatus();setInterval(refreshStatus,3000);statsTimer=setInterval(updateStats,1000);
</script></body></html>
        """.trimIndent()
    }
}
