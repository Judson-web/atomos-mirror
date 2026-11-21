# Atomos Mirror

**Local-network screen mirroring for Android and Windows, designed to work without cloud hosting.**

The Android app hosts its dashboard and signalling endpoint directly on the phone. The Windows companion is a separate desktop app. Both devices must be on the same reachable LAN/Wi-Fi network.

## Current implementation (0.2.0 development)

- Android app with a locally served receiver dashboard.
- Six-digit pairing PIN generated for each receiver session.
- Local WebSocket signalling relay on port 8766. It relays SDP/ICE messages only; media is peer-to-peer.
- Windows Electron companion with desktop/window capture via Electron's display-media API.
- WebRTC sender in Windows and WebRTC receiver in the Android-hosted browser dashboard.
- Windows installer configuration using electron-builder (NSIS).

## Windows companion

Install Node.js 20+ on Windows. In PowerShell, from the repository's `desktop` directory run `npm install`, then `npm start`. To build an installer run `npm run dist`.

Start the receiver in Android, open the displayed HTTP address on the laptop, and note the pairing PIN. Enter the phone's LAN IP and PIN in the Windows companion, connect, then choose **Share desktop** and approve the Windows capture prompt.

## Network

- TCP 8765: Android-hosted dashboard/status.
- TCP 8766: Android-hosted WebSocket signalling.
- WebRTC media: direct peer-to-peer UDP candidates.

Allow the Windows app through Windows Defender Firewall on private networks. Guest Wi-Fi, client isolation, VPNs and some mesh configurations can block local connectivity.

## Honest implementation status

**Implemented in source, not yet device-validated:** Windows desktop capture flow, PIN authentication, WebSocket signalling, and browser WebRTC receiver negotiation.

**Not implemented:** Android-native screen capture and Android-to-Windows sending, foreground-service persistence, native Windows WASAPI controls, automatic discovery, advanced resolution/FPS/bitrate controls, multi-monitor picker and production hardening. Loopback audio capture is best-effort and depends on Windows/Electron capture support. The Windows app currently selects the first available screen/window source.

This is not yet a production-ready release. A successful CI build and real Android/Windows end-to-end test are still required.
