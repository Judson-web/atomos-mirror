package com.atomos.mirror

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** Local-only HTTP dashboard hosted by the Android device. */
class LocalReceiverServer(context: Context, port: Int = 8765, private val pairingPin: String = "------", private val peerSnapshot: () -> JSONArray = { JSONArray() }) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/api/status" -> newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8",
            JSONObject().put("name", "Atomos Mirror").put("status", "online")
                .put("host", localAddress()).put("port", listeningPort)
                .put("signalPort", 8766).put("pairingPin", pairingPin)
                .put("devices", peerSnapshot()).put("deviceCount", peerSnapshot().length())
                .put("transport", "local-webrtc").put("streamingReady", true).toString()
        )
        "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DASHBOARD)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    fun localAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress)
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
<aside class="panel"><h3>Receiver details</h3><div class="stat"><span>Network</span><b>Local Wi-Fi</b></div><div class="stat"><span>Server</span><b id="server">Starting…</b></div><div class="stat"><span>Media stream</span><b>Not connected</b></div><div class="address" id="address">Loading address…</div><button class="copy" onclick="navigator.clipboard?.writeText(location.href)">Copy receiver address</button></aside></section>
<footer>Private local-network dashboard · Served by your Android device · No cloud account required.<br>Pairing uses your six-digit PIN. Media travels directly between devices over WebRTC.</footer></main>
<script>async function refresh(){try{const r=await fetch('/api/status',{cache:'no-store'});const s=await r.json();document.querySelector('#server').textContent=s.status==='online'?'Online':'Offline';document.querySelector('#address').textContent='http://'+s.host+':'+s.port;document.querySelector('#count').textContent='· '+s.devices.length+' devices';const list=document.querySelector('#device-list');if(s.devices.length){list.className='';list.innerHTML=s.devices.map(d=>'<div class=\"stat\"><span>'+String(d.name).replace(/[&<>\"']/g,'')+' · '+d.role+'</span><b>● Connected</b></div>').join('')}else{list.className='empty';list.innerHTML='<div class=\"glyph\">⌁</div><b>No devices connected</b><p class=\"muted\">Open the Windows companion and press Find. This receiver is advertised on your local Wi-Fi network.</p>'}}catch(e){document.querySelector('#server').textContent='Unavailable'}}refresh();setInterval(refresh,3000);</script></body></html>
        <script>
let pc=null,ws=null,iceQueue=[],browserRetry=0,browserRetryTimer=null;
const video=document.createElement('video');
video.autoplay=true;video.playsInline=true;video.controls=true;
video.style.cssText='display:block;width:100%;max-height:65vh;background:#05080f;border-radius:14px;margin:18px 0;object-fit:contain';
const main=document.querySelector('main');
const panel=document.createElement('section');panel.style.cssText='background:#111a2b;border:1px solid #25324a;border-radius:20px;padding:20px;margin-top:18px';
panel.innerHTML='<h3>Live desktop stream</h3><p id="am-state" style="color:#9eabc2">Waiting for Windows companion…</p><div id="am-metrics" style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:12px 0"><div class="stat"><span>Bitrate</span><b id="am-bitrate">—</b></div><div class="stat"><span>FPS</span><b id="am-fps">—</b></div><div class="stat"><span>Loss</span><b id="am-loss">—</b></div><div class="stat"><span>RTT</span><b id="am-rtt">—</b></div></div><p id="am-remote" class="muted" style="font-size:11px">WebRTC diagnostics will appear when streaming starts.</p>';
panel.appendChild(video);main.appendChild(panel);
let amPreviousBytes=0,amPreviousAt=0;
function amMetric(id,value){const el=document.querySelector('#'+id);if(el)el.textContent=value}
async function amUpdateStats(){
 if(!pc){amMetric('am-bitrate','—');amMetric('am-fps','—');amMetric('am-loss','—');amMetric('am-rtt','—');return}
 try{
  const report=await pc.getStats();let bytes=0,fps=0,lost=0,received=0,rtt=null;
  report.forEach(s=>{
   if(s.type==='inbound-rtp'&&(s.kind==='video'||s.mediaType==='video')){bytes+=s.bytesReceived||0;fps=s.framesPerSecond||s.framesDecoded||fps;lost+=s.packetsLost||0;received+=s.packetsReceived||0}
   if(s.type==='outbound-rtp'&&(s.kind==='video'||s.mediaType==='video')){bytes+=s.bytesSent||0;fps=s.framesPerSecond||s.framesEncoded||fps}
   if(s.type==='candidate-pair'&&s.state==='succeeded'&&(s.nominated||s.selected))rtt=s.currentRoundTripTime??rtt
  });
  const now=performance.now(),rate=amPreviousAt&&now>amPreviousAt?Math.max(0,(bytes-amPreviousBytes)*8/((now-amPreviousAt)/1000)):null;
  amPreviousBytes=bytes;amPreviousAt=now;
  amMetric('am-bitrate',rate==null?'Measuring…':rate>=1e6?(rate/1e6).toFixed(2)+' Mbps':Math.round(rate/1000)+' kbps');
  amMetric('am-fps',fps?Math.round(fps)+' FPS':'—');
  amMetric('am-loss',received+lost?((lost/(received+lost))*100).toFixed(2)+'%':'—');
  amMetric('am-rtt',rtt==null?'—':Math.round(rtt*1000)+' ms');
 }catch(e){amMetric('am-remote','Stats unavailable: '+e.message)}
}
setInterval(amUpdateStats,1000);
async function atomosReceiver(){
 const s=await (await fetch('/api/status',{cache:'no-store'})).json();
 let pin=document.querySelector('#am-pin');if(!pin){pin=document.createElement('p');pin.id='am-pin';pin.style.cssText='font-size:24px;letter-spacing:5px;color:#8eafff;font-weight:800';panel.insertBefore(pin,video)}pin.textContent='Pairing PIN: '+s.pairingPin;
 ws=new WebSocket('ws://'+s.host+':'+s.signalPort);
 const send=m=>{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(m))};
 ws.onopen=()=>{browserRetry=0;send({type:'hello',role:'receiver',pin:s.pairingPin,name:'Android browser receiver'})};
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
  else if(m.type==='error')document.querySelector('#am-state').textContent=m.message;
 };
 ws.onclose=()=>{document.querySelector('#am-state').textContent='Signalling disconnected · retrying';if(pc){pc.close();pc=null}video.srcObject=null;clearTimeout(browserRetryTimer);const wait=Math.min(1000*Math.pow(2,browserRetry),20000);browserRetry=Math.min(browserRetry+1,8);browserRetryTimer=setTimeout(()=>atomosReceiver().catch(()=>{}),wait)};
}
atomosReceiver().catch(e=>document.querySelector('#am-state').textContent='Receiver error: '+e.message);
        </script>
        """.trimIndent()
    }
}
