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

function status(text, online = false) {
  $("status").textContent = text.toUpperCase();
  $("status").classList.toggle("online", online);
}
function hint(text) { $("hint").textContent = text; }
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
      if (d.pin) $("pin").value = d.pin;
      hint("Found " + d.name + " at " + d.host + ". Pairing details filled in.");
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
        $("quality").textContent = peer ? peer.connectionState : "Disconnected";
        if (peer && ["failed", "disconnected"].includes(peer.connectionState)) hint("Media connection interrupted. Waiting for the sender to reconnect…");
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
    } else if (message.type === "error") {
      hint(message.message || "Pairing failed.");
      status("Pairing failed");
      if (/PIN/i.test(message.message || "")) reconnectEnabled = false;
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
    $("quality").textContent = peer ? peer.connectionState : "Disconnected";
    if (peer && ["failed", "disconnected"].includes(peer.connectionState)) hint("Media path interrupted. Signalling will attempt to restore the session.");
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
  hint("Screen sharing stopped.");
}
$("connect").addEventListener("click", () => connect("sender"));
$("receive").addEventListener("click", () => connect("receiver"));
$("share").addEventListener("click", share);
$("stop").addEventListener("click", stop);
refreshSources();
