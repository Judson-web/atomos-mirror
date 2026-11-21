const $ = id => document.getElementById(id);
let socket = null;
let peer = null;
let stream = null;
let pendingCandidates = [];

function status(text, online = false) {
  $("status").textContent = text.toUpperCase();
  $("status").classList.toggle("online", online);
}
function hint(text) { $("hint").textContent = text; }
function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}
async function connect() {
  const host = $("host").value.trim().replace(/^https?:\/\//, "").replace(/\/$/, "");
  const pin = $("pin").value.trim();
  if (!host || !/^\d{6}$/.test(pin)) return hint("Enter the Android local IP address and its six-digit pairing PIN.");
  socket = new WebSocket(`ws://${host}:8766`);
  status("Connecting");
  socket.onopen = () => {
    send({ type: "hello", role: "sender", pin, name: "Windows desktop" });
    hint("Connected to the local signalling service. Waiting for pairing confirmation…");
  };
  socket.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (message.type === "joined") {
      status("Paired", true);
      $("share").disabled = false;
      hint("Paired with Atomos Mirror. Choose Share desktop to begin.");
    } else if (message.type === "error") {
      hint(message.message || "Pairing failed.");
      status("Pairing failed");
    } else if (message.type === "answer" && peer) {
      await peer.setRemoteDescription(message.description);
      for (const candidate of pendingCandidates) await peer.addIceCandidate(candidate);
      pendingCandidates = [];
      hint("Desktop stream negotiated. Waiting for video on Android…");
    } else if (message.type === "ice" && peer && message.candidate) {
      const candidate = new RTCIceCandidate(message.candidate);
      if (peer.remoteDescription) await peer.addIceCandidate(candidate);
      else pendingCandidates.push(candidate);
    } else if (message.type === "peer" && message.state === "left") {
      hint("The Android receiver disconnected.");
    }
  };
  socket.onerror = () => { status("Connection error"); hint("Could not reach the Android receiver. Check Wi-Fi, IP address and firewall settings."); };
  socket.onclose = () => { status("Disconnected"); $("share").disabled = true; };
}
async function share() {
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: { ideal: 30, max: 60 } }, audio: true });
    $("preview").srcObject = stream;
    peer = new RTCPeerConnection({ iceServers: [] });
    stream.getTracks().forEach(track => peer.addTrack(track, stream));
    peer.onicecandidate = event => { if (event.candidate) send({ type: "ice", candidate: event.candidate }); };
    peer.onconnectionstatechange = () => { $("quality").textContent = peer.connectionState; };
    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    send({ type: "offer", description: peer.localDescription });
    $("stop").disabled = false;
    $("share").disabled = true;
    hint("Sharing request sent. Keep this app open while the Android receiver connects.");
    stream.getVideoTracks()[0].addEventListener("ended", stop);
  } catch (error) {
    hint(error.name === "NotAllowedError" ? "Screen sharing was cancelled." : `Could not start capture: ${error.message}`);
  }
}
function stop() {
  if (stream) stream.getTracks().forEach(track => track.stop());
  if (peer) peer.close();
  stream = null; peer = null; pendingCandidates = [];
  $("preview").srcObject = null;
  $("stop").disabled = true;
  $("share").disabled = !(socket && socket.readyState === WebSocket.OPEN);
  send({ type: "hangup" });
  $("quality").textContent = "Not sharing";
  hint("Desktop sharing stopped.");
}
$("connect").addEventListener("click", connect);
$("share").addEventListener("click", share);
$("stop").addEventListener("click", stop);
