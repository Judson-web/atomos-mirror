# Atomos Mirror

**Two-way, local-network screen mirroring between Android and Windows.** The Android app hosts its own dashboard and signalling service. The Windows companion is an Electron desktop app. There is no cloud relay or account requirement. Both devices must be on a LAN that allows direct peer-to-peer traffic.

## v0.8.0 implementation

- Android foreground receiver service keeps the local dashboard, WebSocket signalling and mDNS discovery alive independently of the Activity.
- Receiver startup state is restored when the app is reopened, and startup failures are surfaced in the Android UI.
- Windows desktop capture supports displays and individual windows, with selectable 720p/1080p/1440p, 24/30/60 FPS and target bitrate controls.
- Android screen capture uses MediaProjection and a foreground service.
- Both directions use direct WebRTC media transport. Android coordinates signalling but does not relay the video stream.
- Optional Android microphone capture is permission-gated. Windows loopback audio is best-effort.
- Android browser receiver and Windows companion expose live bitrate, FPS, packet-loss and RTT diagnostics where WebRTC provides those metrics.
- Signalling reconnects with bounded backoff and re-negotiates when a peer rejoins.
- Pairing PINs are no longer published through the status API or mDNS metadata. Discovery finds the receiver address only; users enter the six-digit PIN displayed in the Android app.
- Signalling sessions are limited to one sender and one receiver. Failed PIN attempts are rate-limited per remote IP.

## Run the Windows companion

Install Node.js 20 or later. In PowerShell, from `desktop`:

```powershell
npm install
npm test
npm start
```

Build the Windows installer:

```powershell
npm run dist
```

## Windows → Android

1. Start the receiver in the Android app and note the displayed LAN address and six-digit PIN.
2. Open that address in the Android phone's browser. The dashboard is served by the phone itself.
3. Enter the PIN in the browser dashboard and select **Connect receiver**.
4. In the Windows companion, discover the phone or enter its IP and PIN.
5. Choose **Send desktop to Android**, then **Share desktop**, select a display or window and approve capture.

## Android → Windows

1. Start the receiver in the Android app.
2. In the Windows companion, enter the Android IP and PIN, then choose **Receive Android screen**.
3. In the Android app, choose **Share this Android screen to Windows** and approve Android's screen-capture prompt.
4. Stop sharing from either device when finished.

## Network and security

- TCP 8765: Android-hosted dashboard and status API.
- TCP 8766: local WebSocket signalling.
- WebRTC media: direct peer-to-peer transport using local ICE candidates. No STUN or TURN server is configured.
- Allow Atomos Mirror through Windows Defender Firewall on private networks. Guest Wi-Fi, client isolation, VPNs and some mesh networks can block direct connectivity.
- The six-digit pairing PIN is intentionally shared only in the Android app UI, not in mDNS or the status API. The signalling WebSocket currently uses plain `ws://` on the local network, so use a trusted private LAN. WebRTC media is encrypted by DTLS-SRTP after peer negotiation.

## Known limitations

- Android internal playback audio is not captured yet. Android microphone streaming is available as an optional audio track.
- Windows system-audio loopback depends on Electron and Windows support and may not be available on every machine.
- The PIN protects session pairing but is not a substitute for network-level trust; signalling is not TLS-protected.
- mDNS discovery requires multicast support. Manual IP entry remains available.
- WebRTC statistics vary by browser, codec and device, so individual metrics can be unavailable.
- CI verifies compilation, packaging and source-level regression checks. Real-device Android/Windows streaming, audio quality, background behavior and vendor-specific battery restrictions still require physical-device testing.

## Build outputs

The Android CI workflow produces a debug APK. The Windows CI workflow produces an unsigned NSIS installer. Neither artifact is a production-signed release.
