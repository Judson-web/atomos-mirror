# Atomos Mirror

**Version 1.1.0 · Android + Windows · Local-network screen mirroring**

Atomos Mirror streams screens directly between an Android phone and a Windows PC over the same local Wi-Fi/LAN. It does not require a cloud account or a media relay.

This release replaces manual PIN entry with **short-lived QR approval**, rebuilds the Android app into separate Home, Pair, Devices and Settings pages, and updates the browser dashboard and Windows companion to use the same pairing flow.

> **Testing status:** this is an internal release candidate. CI builds and source-level tests must pass before publishing. A successful build is not proof of real-device interoperability. Validate both streaming directions on the target phone, Windows PC and router before calling it production-ready.

## Downloads

The current release is published at:
- [GitHub Releases](https://github.com/Judson-web/atomos-mirror/releases)

Download the Android APK and Windows installer from the latest release Assets section. Check the SHA-256 digest shown by GitHub against the supplied checksum file.

## What changed in 1.1.0

- Android app now has four dedicated sections: Home, Pair, Devices and Settings.
- Android QR scanner uses CameraX and Google ML Kit.
- The Android-hosted web dashboard can generate a pairing QR code.
- Windows companion can discover the Android receiver and display a QR code.
- PINs have been removed from the UI and signalling protocol.
- Pairing now requires approval by scanning a random 256-bit token.
- QR challenges expire after three minutes and are not included in the public status endpoint or mDNS records.
- Windows desktop regression tests validate the QR/token flow and embedded dashboard scripts.

## Pairing and first connection

1. Connect the Android phone and Windows PC to the same private Wi-Fi network. Disable VPNs for the first test.
2. Open Atomos Mirror on Android and start the receiver from **Home**.
3. On Windows, open the Atomos Mirror companion and press **Find**. If discovery is blocked, enter the phone's local IPv4 address and press **Generate QR**.
4. The Windows companion requests a temporary challenge from the Android receiver and displays a QR code.
5. In the Android app, open **Pair** and scan the QR displayed on the PC.
6. Wait for the app to show **Computer approved**.
7. Back on Windows, choose **Send desktop to Android** or **Receive Android screen**. The app checks that the QR was approved before opening signalling.
8. For Windows-to-Android, press **Share desktop** and select the screen or window. For Android-to-Windows, use **Share this phone screen** on Android and approve Android's system screen-capture prompt.

The QR is a one-time session approval, not a permanent device credential. Generate a fresh QR when it expires or when starting a new pairing session. Do not share a QR screenshot with anyone you do not intend to authorize on your local network.

### Browser dashboard

The Android receiver also serves a local web dashboard at:

`http://PHONE-IP:8765`

Open it on a PC browser on the same LAN. Press **Generate pairing QR**, scan it from the Android app's **Pair** page, then use **Connect receiver** in the browser dashboard. The browser dashboard can receive a Windows desktop stream without installing the Windows companion.

## Android app pages

- **Home:** receiver status, local address, microphone preference and phone screen-sharing controls.
- **Pair:** camera scanner for the QR shown by the Windows companion or browser dashboard.
- **Devices:** live list of peers connected to the receiver.
- **Settings:** microphone preference, receiver launch preference and app information.

Camera permission is used only by the QR scanner. Microphone permission is requested only when microphone audio is enabled. Android's MediaProjection system prompt is required each time screen sharing starts.

## How it works

- Android hosts an HTTP dashboard/API on TCP port **8765**.
- Android hosts a WebSocket signalling coordinator on TCP port **8766**.
- Android advertises the receiver over mDNS as `_atomosmirror._tcp.`.
- Windows uses Electron, `desktopCapturer`, WebRTC and mDNS discovery.
- Android screen capture uses MediaProjection and WebRTC.
- Media travels directly between the two devices using WebRTC DTLS-SRTP. The signalling server relays negotiation messages, not video frames.
- ICE server list is empty by design: this build targets same-LAN connectivity and does not include STUN/TURN relays.

### QR pairing security

The Android receiver creates a random 256-bit challenge that expires after 180 seconds. The QR contains the local receiver address, signalling port, expiry and token. The Android app validates the QR format and receiver address, then explicitly approves the token. The WebSocket server accepts peers only after that approval. The session permits one sender and one receiver.

The HTTP dashboard is served over local plain HTTP and signalling uses local plain WebSocket. The random QR token and explicit approval improve pairing UX and prevent silent joins, but they do not replace a trusted network. Use a private LAN and do not expose ports 8765/8766 to the public internet.

## Troubleshooting

**Windows cannot find the Android receiver**
- Confirm the receiver is running and both devices share the same LAN.
- Allow mDNS/multicast and client-to-client traffic on the router.
- Enter the phone's IPv4 address manually and generate the QR.
- Guest Wi-Fi, client isolation, VPNs and some hotspots block discovery or direct traffic.

**QR scan is rejected**
- Generate a fresh QR on the PC. Challenges expire after three minutes.
- Confirm the QR belongs to the same Android receiver currently running.
- Open Pair in the Android app and allow camera permission.
- If the phone changed Wi-Fi networks, restart the receiver and generate a new QR.

**The PC says it is waiting for approval**
- Keep the QR visible and scan it in the Android app.
- Wait for the app to display **Computer approved**, then press the connection direction button again.

**Devices pair but video does not appear**
- Start with Windows-to-Android at 720p/30 FPS.
- Ensure the receiver is running before connecting.
- Allow Atomos Mirror through Windows Defender Firewall on Private networks.
- Disable VPNs and verify router client isolation is off.
- Both peers need a direct LAN route. No TURN relay is configured.

**Android screen sharing stops**
- Keep the MediaProjection foreground notification active.
- Approve the Android screen-capture prompt each time.
- Set Atomos Mirror battery usage to unrestricted during long tests if the phone aggressively stops background services.

**Windows installer shows a publisher warning**
- The internal testing installer may use a self-signed certificate. It is not a publicly trusted publisher identity.
- Do not import a certificate unless it came from the same trusted release artifact.
- Public distribution requires a trusted Authenticode certificate.

## Known limitations

- Android internal playback audio capture is not implemented. Optional microphone capture is available.
- Windows loopback audio depends on Windows/Electron support and device policy.
- No STUN/TURN relay is configured. Same-LAN direct connectivity is required.
- Local HTTP and WebSocket signalling are not TLS-protected. Use a trusted private network.
- mDNS discovery depends on multicast; manual IPv4 entry is the fallback.
- Real-device compatibility, extended-session stability, thermal/battery behavior, audio quality and different router/firewall configurations still need validation.

## Real-device acceptance checklist

Do not mark these complete based only on CI:

- [ ] Install the new signed APK and Windows installer.
- [ ] Start Android receiver and open `http://PHONE-IP:8765` from Windows.
- [ ] Generate a QR in the browser dashboard and scan it from Android.
- [ ] Generate a QR in the Windows companion and scan it from Android.
- [ ] Verify expired QR codes are rejected and fresh QR codes work.
- [ ] Stream Windows → Android for at least 15 minutes at 720p/30 FPS.
- [ ] Stream Android → Windows for at least 15 minutes at 720p/30 FPS.
- [ ] Repeat at 1080p/30 FPS and inspect bitrate, FPS, packet loss and RTT.
- [ ] Stop and restart both directions without rebooting either device.
- [ ] Toggle Wi-Fi and verify the app reports a useful recovery state.
- [ ] Test Android app backgrounding, screen lock and battery restrictions.
- [ ] Test Windows Firewall with Private-network permission only.

## Build from source

### Android

Requirements: JDK 17, Android SDK 36 and Gradle 8.13.

```bash
gradle assembleDebug
```

For a signed release build, configure these environment variables:
- `ATOMOS_SIGNING_STORE_FILE`
- `ATOMOS_SIGNING_STORE_PASSWORD`
- `ATOMOS_SIGNING_KEY_ALIAS`
- `ATOMOS_SIGNING_KEY_PASSWORD`

Then run:

```bash
gradle assembleRelease
```

### Windows

Requirements: Windows 10/11, Node.js 22 and npm.

```bash
cd desktop
npm install
npm test
npm start
npm run dist
```

The signed release workflow uses the repository's Android keystore and Windows code-signing PFX secrets when configured. If signing secrets are absent, it generates temporary internal-testing identities. Preserve those identities securely: Android does not accept an update signed by a different key.

## Release workflow

The **Signed Release Candidate** workflow runs on pushes to `main` and can be started manually from GitHub Actions. It builds and verifies the signed Android APK, runs desktop regression tests, builds the Windows installer and uploads release artifacts.

Before describing any build as production-ready, complete the real-device acceptance checklist, preserve stable signing identities, and use a trusted Windows publisher certificate for public distribution.
