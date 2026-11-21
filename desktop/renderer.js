const $ = id => document.getElementById(id);
let socket = null;
let peer = null;
let stream = null;
let pendingCandidates = [];
let activeRole = "sender";
let reconnectAttempts = 0;
let reconnectTimer = null;
let reconnectEnabled = false;
let lastPairing = null;
let statsTimer = null;
let previousStats = null;
let previousStatsAt = 0;

function status(text, online = false) {
  $("status").textContent = text.toUpperCase();
  $("status").classList.toggle("online", online);
}
function hint(text) { $("hint").textContent = text; }
function diag(id, value) { const node = $(id); if (node) node.textContent = value; }
function explainConnection(state, iceState) {
  if (state === "connected") return "Media path is connected. Metrics update every second.";
  if (state === "connecting" || iceState === "checking") return "Negotiating a direct local network path. Check that both devices are on the same Wi-Fi.";
  if (state === "disconnected") return "Network interruption detected. Keep both devices awake and on the same Wi-Fi; recovery is being attempted.";
  if (state === "failed") return "WebRTC path failed. Check Wi-Fi client isolation, VPNs, and firewall rules, then reconnect.";
  if (state === "closed") return "Media connection is closed.";
  return "Waiting for a WebRTC media connection.";
}
async function updateDiagnostics() {
  const current = peer;
  if (!current) {
    diag("diag-state", "Not connected"); diag("diag-bitrate", "—"); diag("diag-fps", "—");
    diag("diag-loss", "—"); diag("diag-rtt", "—"); diag("diag-advice", "Connect and start a stream to collect live metrics.");
    previousStats = null; previousStatsAt = 0; return;
  }
  try {
    const report = await current.getStats();
    let bytes = 0, fps = 0, frames = 0, lost = 0, received = 0, rtt = null, codec = "";
    report.forEach(stat => {
      if (stat.type === "outbound-rtp" && !stat.isRemote && (stat.kind === "video" || stat.mediaType === "video")) {
        bytes += stat.bytesSent || 0; fps = stat.framesPerSecond || fps; frames += stat.framesEncoded || 0;
      }
      if (stat.type === "inbound-rtp" && (stat.kind === "video" || stat.mediaType === "video")) {
        bytes += stat.bytesReceived || 0; fps = stat.framesPerSecond || fps; frames += stat.framesDecoded || 0;
        lost += stat.packetsLost || 0; received += stat.packetsReceived || 0;
      }
      if (stat.type === "candidate-pair" && (stat.state === "succeeded") && (stat.nominated || stat.selected)) rtt = stat.currentRoundTripTime ?? rtt;
      if (stat.type === "codec" && stat.mimeType) codec = stat.mimeType;
    });
    const now = performance.now();
    let rate = null;
    let intervalFps = fps;
    if (previousStats && previousStatsAt && now > previousStatsAt) { const seconds=(now-previousStatsAt)/1000; rate = Math.max(0, (bytes - previousStats.bytes) * 8 / seconds); if (!intervalFps) intervalFps=Math.max(0,(frames-previousStats.frames)/seconds); }
    previousStats = { bytes, frames }; previousStatsAt = now;
    const state = current.connectionState || current.iceConnectionState || "unknown";
    diag("diag-state", state.toUpperCase());
    diag("diag-bitrate", rate == null ? "Measuring…" : (rate >= 1000000 ? (rate / 1000000).toFixed(2) + " Mbps" : Math.round(rate / 1000) + " kbps"));
    diag("diag-fps", intervalFps ? Math.round(intervalFps) + " FPS" : "—");
    diag("diag-loss", received + lost ? ((lost / (received + lost)) * 100).toFixed(2) + "%" : "—");
    diag("diag-rtt", rtt == null ? "—" : Math.round(rtt * 1000) + " ms");
    diag("diag-advice", explainConnection(state, current.iceConnectionState) + (codec ? " Codec: " + codec + "." : ""));
  } catch (error) { diag("diag-state", "Stats unavailable"); diag("diag-advice", "The browser could not read WebRTC statistics: " + error.message); }
}
function startDiagnostics() {
  if (statsTimer) clearInterval(statsTimer);
  statsTimer = setInterval(updateDiagnostics, 1000);
  updateDiagnostics();
}

function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}
async function refreshSources() {
  const select = $("source");
  select.innerHTML = '<option value="">Loading displays…</option>';
  try {
    const sources = await window.atomos.listSources();
    select.innerHTML = "";
    for (const source of sources) {
      const option = document.createElement("option");
      option.value = source.id;
      option.textContent = source.type + " · " + source.name;
      select.appendChild(option);
    }
    if (!sources.length) select.innerHTML = '<option value="">No capture sources found</option>';
    else await window.atomos.selectSource(select.value);
  } catch (error) {
    select.innerHTML = '<option value="">Source list unavailable</option>';
    hint("Could not enumerate capture sources: " + error.message);
  }
}
$("source").addEventListener("change", () => window.atomos.selectSource($("source").value));
$("refresh-sources").addEventListener("click", refreshSources);
$("discover").addEventListener("click", async () => {
  $("discover").disabled = true;
  hint("Searching the local Wi-Fi network for Atomos Mirror…");
  try {
    const devices = await window.atomos.discover();
    if (!devices.length) hint("No receiver found. Check that both devices share Wi-Fi and the Android receiver is running.");
    else {
      const d = devices[0];
      $("host").value = d.host;
      hint("Found " + d.name + " at " + d.host + ". Enter the six-digit PIN shown on the Android device.");
    }
  } catch (error) { hint("Discovery failed: " + error.message); }
  finally { $("discover").disabled = false; }
});

async function connect(role = "sender", retry = false) {
  activeRole = role;
  const host = retry && lastPairing ? lastPairing.host : $("host").value.trim().replace(/^https?:\/\//, "").replace(/\/$/, "");
  const pin = retry && lastPairing ? lastPairing.pin : $("pin").value.trim();
  if (!host || !/^\d{6}$/.test(pin)) return hint("Enter the Android local IP address and its six-digit pairing PIN.");
  if (!retry) {
    reconnectEnabled = true;
    reconnectAttempts = 0;
    lastPairing = { host, pin };
    if (socket) { const old = socket; socket = null; old.close(); }
    if (peer) peer.close();
    peer = null;
    pendingCandidates = [];
  }
  clearTimeout(reconnectTimer);
  const ws = new WebSocket("ws://" + host + ":8766");
  socket = ws;
  status(retry ? "Reconnecting" : "Connecting");
  ws.onopen = () => {
    reconnectAttempts = 0;
    ws.send(JSON.stringify({ type: "hello", role, pin, name: role === "sender" ? "Windows desktop sender" : "Windows desktop receiver" }));
    hint(retry ? "Signalling restored. Rejoining the local session…" : "Connecting to the local Atomos Mirror session…");
  };
  ws.onmessage = async event => {
    if (ws !== socket) return;
    const message = JSON.parse(event.data);
    if (message.type === "joined") {
      status("Paired", true);
      $("share").disabled = role !== "sender" || !stream;
      hint(role === "sender" ? "Paired. Choose Share desktop to send this PC's screen." : "Paired. Waiting for the Android phone to start sharing.");
      if (role === "sender" && stream) await negotiateDesktopStream();
    } else if (message.type === "offer" && role === "receiver") {
      if (peer) peer.close();
      pendingCandidates = [];
      peer = new RTCPeerConnection({ iceServers: [] });
      $("preview").muted = false;
      peer.ontrack = event => {
        $("preview").srcObject = event.streams[0];
        $("preview").play().catch(() => {});
        hint("Receiving Android screen and available audio.");
      };
      peer.onicecandidate = event => { if (event.candidate) send({ type: "ice", candidate: event.candidate }); };
      peer.onconnectionstatechange = () => {
        const state = peer ? peer.connectionState : "closed";
        $("quality").textContent = state;
        startDiagnostics();
        if (["failed", "disconnected"].includes(state)) hint(explainConnection(state, peer?.iceConnectionState));
      };
      peer.oniceconnectionstatechange = () => {
        if (peer && ["failed", "disconnected"].includes(peer.iceConnectionState))
          hint(explainConnection(peer.connectionState, peer.iceConnectionState));
      };
      await peer.setRemoteDescription(message.description);
      for (const candidate of pendingCandidates) await peer.addIceCandidate(candidate);
      pendingCandidates = [];
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      send({ type: "answer", description: peer.localDescription });
      $("stop").disabled = false;
    } else if (message.type === "answer" && peer && role === "sender") {
      await peer.setRemoteDescription(message.description);
      for (const candidate of pendingCandidates) await peer.addIceCandidate(candidate);
      pendingCandidates = [];
      hint("Desktop stream negotiated. Waiting for video on Android…");
    } else if (message.type === "ice" && message.candidate) {
      const candidate = new RTCIceCandidate(message.candidate);
      if (peer && peer.remoteDescription) await peer.addIceCandidate(candidate);
      else pendingCandidates.push(candidate);
    } else if (message.type === "hangup") {
      if (peer) peer.close();
      peer = null;
      $("preview").srcObject = null;
      $("stop").disabled = true;
      $("share").disabled = role !== "sender";
      hint("The other device stopped sharing.");
    } else if (message.type === "diagnostics") {
      if (activeRole === "receiver") {
        const remote = "Android sender telemetry · " + (message.bitrateBps >= 0 ? (message.bitrateBps / 1000000).toFixed(2) + " Mbps" : "bitrate measuring") +
          " · " + (message.fps ? Math.round(message.fps) + " FPS" : "FPS unavailable") +
          " · " + (message.rttMs >= 0 ? message.rttMs + " ms RTT" : "RTT unavailable");
        diag("diag-advice", remote + " | " + explainConnection(peer?.connectionState, peer?.iceConnectionState));
      }
    } else if (message.type === "error") {
      hint(message.message || "Pairing failed.");
      status("Pairing failed");
      if (/PIN/i.test(message.message || "")) reconnectEnabled = false;
    } else if (message.type === "peer" && message.state === "joined") {
      hint((message.name || "A device") + " joined the session.");
      if (role === "sender" && stream) await negotiateDesktopStream();
    } else if (message.type === "peer" && message.state === "left") {
      hint((message.name || "The paired device") + " disconnected.");
    }
  };
  ws.onerror = () => {
    if (ws !== socket) return;
    status("Connection error");
    hint("Could not reach Android. Checking the local connection…");
  };
  ws.onclose = () => {
    if (ws !== socket) return;
    status("Disconnected");
    $("share").disabled = true;
    if (peer) { peer.close(); peer = null; }
    pendingCandidates = [];
    if (reconnectEnabled && lastPairing) {
      const wait = Math.min(1000 * Math.pow(2, reconnectAttempts), 20000);
      reconnectAttempts++;
      hint("Signalling disconnected. Automatic retry in " + Math.ceil(wait / 1000) + "s…");
      reconnectTimer = setTimeout(() => connect(activeRole, true), wait);
    }
  };
}
async function negotiateDesktopStream() {
  if (!stream || !socket || socket.readyState !== WebSocket.OPEN) return;
  if (peer) peer.close();
  pendingCandidates = [];
  const [width, height] = $("resolution").value.split("x").map(Number);
  const fps = Number($("fps").value);
  const bitrate = Number($("bitrate").value);
  peer = new RTCPeerConnection({ iceServers: [] });
  stream.getTracks().forEach(track => {
    const sender = peer.addTrack(track, stream);
    if (track.kind === "video") {
      const parameters = sender.getParameters();
      parameters.encodings = parameters.encodings && parameters.encodings.length ? parameters.encodings : [{}];
      parameters.encodings[0].maxBitrate = bitrate;
      parameters.encodings[0].maxFramerate = fps;
      parameters.degradationPreference = "maintain-resolution";
      sender.setParameters(parameters).catch(() => {});
    }
  });
  peer.onicecandidate = event => { if (event.candidate) send({ type: "ice", candidate: event.candidate }); };
  peer.onconnectionstatechange = () => {
    const state = peer ? peer.connectionState : "closed";
    $("quality").textContent = state;
    startDiagnostics();
    if (["failed", "disconnected"].includes(state)) hint(explainConnection(state, peer?.iceConnectionState));
  };
  peer.oniceconnectionstatechange = () => {
    if (peer && ["failed", "disconnected"].includes(peer.iceConnectionState))
      hint(explainConnection(peer.connectionState, peer.iceConnectionState));
  };
  const offer = await peer.createOffer();
  await peer.setLocalDescription(offer);
  send({ type: "offer", description: peer.localDescription });
  $("stop").disabled = false;
  $("share").disabled = true;
  hint("Sharing at up to " + width + "×" + height + " · " + fps + " FPS · " + (bitrate / 1000000) + " Mbps.");
}
async function share() {
  try {
    if (!socket || socket.readyState !== WebSocket.OPEN) return hint("Connect to the Android receiver first.");
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: { width: { ideal: Number($("resolution").value.split("x")[0]) }, height: { ideal: Number($("resolution").value.split("x")[1]) }, frameRate: { ideal: Number($("fps").value), max: Number($("fps").value) } },
      audio: true
    });
    $("preview").muted = true;
    $("preview").srcObject = stream;
    await negotiateDesktopStream();
    stream.getVideoTracks()[0].addEventListener("ended", stop);
  } catch (error) {
    hint(error.name === "NotAllowedError" ? "Screen sharing was cancelled." : "Could not start capture: " + error.message);
  }
}
function stop() {
  reconnectEnabled = !!(socket && socket.readyState === WebSocket.OPEN);
  if (stream) stream.getTracks().forEach(track => track.stop());
  if (peer) peer.close();
  stream = null; peer = null; pendingCandidates = [];
  $("preview").srcObject = null;
  $("preview").muted = true;
  $("stop").disabled = true;
  $("share").disabled = !(activeRole === "sender" && socket && socket.readyState === WebSocket.OPEN);
  send({ type: "hangup" });
  $("quality").textContent = "Not sharing";
  updateDiagnostics();
  hint("Screen sharing stopped.");
}
$("connect").addEventListener("click", () => connect("sender"));
$("receive").addEventListener("click", () => connect("receiver"));
$("share").addEventListener("click", share);
$("stop").addEventListener("click", stop);
refreshSources();
