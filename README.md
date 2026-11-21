# Atomos Mirror

**Version 1.2.1 · Android phone to laptop browser · Local Wi-Fi**

Atomos Mirror mirrors an Android phone screen to a laptop browser. The Android app hosts a local dashboard, the laptop opens that page in a browser, and a one-time QR code pairs the two devices. There is no Windows companion, desktop installer, cloud account, or reverse laptop-to-phone mode.

## How to connect

1. Install and open the Atomos Mirror APK on the Android phone.
2. Connect the phone and laptop to the same private Wi-Fi network.
3. In the Android app, open **Home** and start the local receiver.
4. The app displays a local address such as \`http://192.168.1.25:8765\`.
5. Type that address into Chrome, Edge, Firefox or another modern browser on the laptop.
6. On the laptop dashboard, choose **Generate pairing QR**.
7. On the phone, open **Pair**, allow camera access, and scan the QR displayed on the laptop.
8. The dashboard reports phone approval. Choose **Connect phone** on the laptop.
9. On Android, return to **Home** and tap **Share this phone screen**. Approve Android's screen-sharing prompt.
10. The live phone display appears in the laptop browser. Use the dashboard for fullscreen, fit mode, volume, diagnostics and video quality profiles.

The pairing QR is a random, one-time token that expires after three minutes. Generate a new one if it expires or if you want to pair a different laptop browser session.

## Features

- **Android-to-laptop video:** MediaProjection captures the phone display; WebRTC sends video directly over the local network.
- **Browser receiver:** no laptop installation is required. The dashboard is served by the Android phone and opened from the laptop browser.
- **QR pairing:** phone camera scanning, explicit approval, short token lifetime and no manually typed PIN.
- **Internal device audio:** optional Android playback capture for media and game usages on Android 10+. A second Android screen-capture consent is requested for the audio capture session.
- **Streaming profiles:** Smooth (720p/30), Balanced (1080p/30), and High (up to 1080p/60). The dashboard sends profile changes to the phone.
- **Playback controls:** fullscreen, fit/cover, volume and mute.
- **Diagnostics:** connection state, bitrate, FPS, packet loss and round-trip time.
- **LAN-only transport:** video uses direct WebRTC. Internal audio uses a bounded PCM side-channel over the paired local WebSocket session.

## Internal audio capture notes

Internal audio uses Android's \`AudioPlaybackCaptureConfiguration\` API. It is not microphone recording. Android requires the \`RECORD_AUDIO\` runtime permission and a separate MediaProjection consent for playback capture.

Capture is limited by Android policy:
- The source app must allow playback capture. Some apps opt out.
- Protected/DRM playback, calls, voice-communication audio and some system sounds are not captured.
- Android 10 (API 29) or newer is required for internal playback capture. The video-only mode remains available on older supported Android versions.
- If a source app blocks capture, the video stream can still work while the audio indicator remains idle or silent.
- The browser may require a click on **Connect phone** before it allows audio playback.

## Streaming profiles

| Profile | Target capture | Intended use |
|---|---:|---|
| Smooth | 1280 × 720 at 30 FPS | Lower bandwidth or weaker Wi-Fi |
| Balanced | 1920 × 1080 at 30 FPS | Recommended starting point |
| High | Up to 1920 × 1080 at 60 FPS | Fast local network and capable phone |

Actual FPS and bitrate depend on the phone's display, encoder, thermal state, browser, Wi-Fi and receiver performance. The dashboard reports measured values rather than promising fixed throughput.

## Network requirements

- Both devices must be on the same private LAN.
- The router must permit traffic between Wi-Fi clients. Guest networks and client isolation commonly prevent this.
- Android hosts HTTP on TCP **8765** and WebSocket signalling/audio on TCP **8766**.
- No STUN/TURN relay is configured. The WebRTC media path must be direct between the devices.
- VPNs, hotspot isolation, firewall rules and enterprise Wi-Fi policies can block discovery or peer traffic.

## Security

Pairing requires Android-side approval of a random 256-bit token. The challenge expires after 180 seconds, is not exposed in the public status endpoint, and is rate-limited on failed attempts. The signalling server allows one sender and one browser receiver per session.

This is a private-LAN application, not an internet-facing service. The local dashboard uses HTTP and the signalling endpoint uses WebSocket without TLS. Use a trusted network and do not forward ports 8765 or 8766 to the public internet. The QR code is a temporary authorization secret; do not share screenshots of it.

## Troubleshooting

**Laptop cannot open the dashboard**
- Confirm the receiver is running and the address is typed exactly, including port 8765.
- Check that both devices are on the same Wi-Fi network.
- Disable VPNs temporarily and check router client isolation.
- Try the phone's current Wi-Fi IPv4 address if it changed after reconnecting.

**QR scan is rejected**
- Generate a fresh QR on the laptop.
- Make sure the phone's Pair page is scanning the QR from the dashboard currently open on the laptop.
- Keep the receiver running and both devices on the same network.

**Laptop connects but video does not appear**
- On Android, return to Home and tap Share this phone screen.
- Approve the Android screen-sharing prompt.
- Start with Smooth quality and disable VPNs.
- Ensure the laptop browser is not blocking local network/WebSocket connections.

**Video works but internal audio is silent**
- Enable Internal device audio in Android settings before starting the share.
- Grant microphone/record-audio permission when Android requests it. This permission is required by Android's playback-capture API; it does not mean Atomos is using the microphone for internal audio.
- Approve the second Android capture prompt.
- Test with a media or game app that permits playback capture. Protected audio and apps that opt out cannot be captured.
- Click Connect phone in the laptop browser to unlock audio playback.

**Stream stutters**
- Switch to Smooth (720p/30).
- Keep the phone cool, close heavy background apps and move closer to the router.
- Check bitrate, FPS, packet loss and RTT in the dashboard.

## Build from source

Requirements: JDK 17, Android SDK 36 and Gradle 8.13.

\`\`\`bash
gradle assembleDebug
\`\`\`

For a signed release build, configure:
- \`ATOMOS_SIGNING_STORE_FILE\`
- \`ATOMOS_SIGNING_STORE_PASSWORD\`
- \`ATOMOS_SIGNING_KEY_ALIAS\`
- \`ATOMOS_SIGNING_KEY_PASSWORD\`

Then run:

\`\`\`bash
gradle assembleRelease
\`\`\`

The GitHub Actions release workflow builds and verifies the signed Android APK. If no persistent Android signing secrets are configured, it generates a temporary internal-testing keystore and uploads the signing identity as a short-retention artifact. Preserve that keystore and its passwords securely. An APK signed with a different key cannot update the installed app normally.

## Real-device acceptance checklist

- [ ] Install the signed APK on the target Android phone.
- [ ] Open the phone-hosted dashboard from the laptop browser.
- [ ] Generate a QR on the laptop and scan it with the Android Pair page.
- [ ] Confirm the dashboard sees approval and connects.
- [ ] Start Android screen sharing and verify video appears in the laptop browser.
- [ ] Test Smooth, Balanced and High profiles.
- [ ] Test fullscreen, fit mode, volume and mute.
- [ ] Test internal audio with a playback-capture-permitted media/game app.
- [ ] Confirm protected or capture-blocked apps do not falsely report audio capture.
- [ ] Run a 20-minute session and inspect thermal behavior, FPS, bitrate, packet loss and audio stability.
- [ ] Repeat after Wi-Fi reconnect and receiver restart.

A successful CI build verifies compilation and signing, not real-device interoperability. Complete the checklist on the actual phone, laptop and router before treating the build as production-validated.
