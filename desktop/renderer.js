const $ = id => document.getElementById(id);
let socket = null;
let peer = null;
let stream = null;
let pendingCandidates = [];
let activeRole = "sender";

function status(text, online = false) {
  $("status").textContent = text.toUpperCase();
  $("status").classList.toggle("online", online);
}
function hint(text) { $("hint").textContent = text; }
function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}
async function connect(role = "sender") {
  activeRole = role;
  const host = $("host").value.trim().replace(/^https?:\/\//, "").replace(/\/$/, "");
  const pin = $("pin").value.trim();
  if (!host || !/^\d{6}$/.test(pin)) return hint("Enter the Android local IP address and its six-digit pairing PIN.");
  socket = new WebSocket("ws://" + host + ":8766");
  status("Connecting");
  socket.onopen = () => {
    send({ type: "hello", role, pin, name: role === "sender" ? "Windows desktop sender" : "Windows desktop receiver" });
    hint("Connecting to the local Atomos Mirror session…");
  };
  socket.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (message.type === "joined") {
      status("Paired", true);
      $("share").disabled = role !== "sender";
      hint(role === "sender" ? "Paired. Choose Share desktop to send this PC's screen." : "Waiting for the Android phone to start sharing its screen…");
    } else if (message.type === "offer" && role === "receiver") {
      peer = new RTCPeerConnection({ iceServers: [] });
      $("preview").muted = false;
      peer.ontrack = event => {
        $("preview").srcObject = event.streams[0];
        $("preview").play().catch(() => {});
        hint("Receiving Android screen and available audio.");
      };
      peer.onicecandidate = event => { if (event.candidate) send({ type: "ice", candidate: event.candidate }); };
      peer.onconnectionstatechange = () => { $("quality").textContent = peer.connectionState; };
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
    } else if (message.type === "peer" && message.state === "left") {
      hint("The paired device disconnected.");
    }
  };
  socket.onerror = () => { status("Connection error"); hint("Could not reach Android. Check Wi-Fi, IP address and firewall settings."); };
  socket.onclose = () => { status("Disconnected"); $("share").disabled = true; };
}
async function share() {
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: { ideal: 30, max: 60 } }, audio: true });
    $("preview").muted = true;
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
    hint(error.name === "NotAllowedError" ? "Screen sharing was cancelled." : "Could not start capture: " + error.message);
  }
}
function stop() {
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
