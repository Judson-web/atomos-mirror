# Atomos Mirror

**Two-way local-network screen mirroring for Android and Windows.** The Android app hosts its dashboard and signalling service directly on the phone. The Windows companion is a local desktop app. No cloud hosting or account is required; both devices must share a reachable LAN/Wi-Fi network.

## Implemented in source (0.4.0 development)

- Android-hosted dashboard on TCP 8765 and WebSocket signalling on TCP 8766.
- Six-digit session PIN required for both signalling peers.
- WebRTC direct peer-to-peer media negotiation. The Android phone is a signalling coordinator, not a media relay.
- Windows Electron companion with two modes: send desktop to Android, or receive Android screen.
- Android MediaProjection screen capture through a foreground service and WebRTC native sender.
- Windows desktop capture through Electron display-media capture, with best-effort loopback audio.
- Windows source picker for individual displays and windows, including multi-monitor systems.
- Windows capture quality controls for 720p/1080p/1440p, 24/30/60 FPS and 2/4/8/12 Mbps target video bitrate.
- mDNS/DNS-SD advertisement from Android and one-click discovery from the Windows companion.
- Optional Android microphone audio track with an explicit runtime permission prompt.
- Browser receiver page served from the Android app for Windows-to-Android mirroring.

## Windows companion

Install Node.js 20 or later. In PowerShell, from the repository's `desktop` directory:

```powershell
npm install
npm start
```

To package a Windows installer:

```powershell
npm run dist
```

## Windows → Android

1. Start the local receiver in the Android app.
2. Open the displayed `http://phone-ip:8765` address in a laptop browser once.
3. Enter the phone's LAN IP and six-digit pairing PIN in the Windows companion.
4. Choose **Send desktop to Android**, then **Share desktop** and approve capture.

## Android → Windows

1. Start the local receiver in the Android app.
2. Open the Windows companion and enter the phone's LAN IP and pairing PIN.
3. Choose **Receive Android screen**.
4. On Android, choose **Share this Android screen to Windows** and approve the system screen-capture prompt.

## Network

- TCP 8765: Android-hosted dashboard and status API.
- TCP 8766: PIN-authenticated signalling.
- WebRTC media: direct peer-to-peer UDP candidates. Local host candidates are used; no TURN relay is configured.

Allow Atomos Mirror through Windows Defender Firewall on private networks. Guest Wi-Fi, client isolation, VPNs and some mesh configurations can prevent direct peer connectivity.

## Current limitations and validation

Android microphone streaming is optional and permission-gated; Android internal playback audio is not yet captured. Windows system-audio capture is best-effort and depends on Electron and Windows support. The Windows quality controls set capture constraints and a target sender bitrate, but actual negotiated quality depends on the display, codec, network and receiver. mDNS discovery depends on multicast being permitted by the Wi-Fi network. Connection recovery and receiver-service persistence still need work.

Source code and CI builds are not substitutes for an end-to-end device test. Validate on a real Android phone and Windows 10/11 machine before treating this as a stable release.


## v0.5.0 receiver lifecycle

The Android LAN receiver is now owned by a dedicated foreground service rather than the launcher Activity. Starting the receiver creates a persistent notification and keeps the HTTP dashboard, WebSocket signalling endpoint, and mDNS advertisement alive when the app is backgrounded or the Activity is recreated. Stopping the receiver explicitly tears down those resources. This lifecycle change has been added to the Android CI build; real-device background/foreground behavior still needs verification on target Android versions and vendor battery-management settings.


## v0.6.0 live connection state and recovery

The signalling server now maintains a live registry of paired devices, including display name, role, connection state and connection timestamp. The local dashboard reads this registry through `/api/status` and updates its connected-device list while running. The Windows companion and Android screen sender retry lost signalling connections with bounded exponential backoff. Active streams are re-negotiated when a peer rejoins. The browser receiver also retries signalling connections with bounded exponential backoff. These changes improve recovery from transient Wi-Fi or app interruptions; they do not guarantee seamless media continuity, and real-device/network testing remains required.


## v0.7.0 live connection diagnostics

The Windows companion and Android browser receiver now collect WebRTC statistics while a peer connection is active: measured video bitrate, frame rate, packet loss (on receiving peers), and round-trip time when the selected ICE candidate pair exposes it. Android's MediaProjection sender also samples its WebRTC statistics and relays sender telemetry to paired clients over the authenticated local signalling channel. Connection-state and ICE failures now display actionable troubleshooting guidance. Metrics are sampled approximately once per second; individual fields may show unavailable when a WebRTC implementation does not expose that statistic. These diagnostics do not replace real device/network validation.
