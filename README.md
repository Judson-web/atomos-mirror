# Atomos Mirror

**Atomos Mirror 1.0.0** is a two-way screen-mirroring system for Android and Windows. Both devices communicate directly over the same local Wi-Fi/LAN. The Android app hosts the receiver dashboard and signalling service; the Windows companion captures or receives the desktop stream. There is no cloud relay, account, or internet service in the media path.

> **Release status:** 1.0.0 signed internal-testing builds are produced by GitHub Actions. The first-run signing identities are self-generated for testing unless repository signing secrets are configured. A self-signed Windows certificate is not a publicly trusted publisher certificate. Complete the real-device test plan below before treating this as a production-ready public release.

## 1. What you need

- An Android device running Android 8.0 (API 26) or later.
- A Windows 10/11 PC.
- Both devices connected to the same private Wi-Fi network or LAN.
- A network that allows devices to communicate with one another. Guest Wi-Fi and client/AP isolation commonly block this.
- Windows firewall permission for Atomos Mirror on **Private networks**.

Internet access is not required after the app and desktop installer have been downloaded. The first Windows installation may need internet access if Windows needs to retrieve a missing runtime or certificate chain.

## 2. Download the signed testing builds

Open the repository's **Actions** tab and select the latest successful **Signed Release Candidate** run on main.

Download these artifacts:

| Artifact | Contents |
|---|---|
| atomos-mirror-android-v1.0.0 | Signed Android APK and SHA-256 checksum |
| atomos-mirror-windows-v1.0.0 | Signed Windows NSIS installer, public signing certificate and checksum |
| atomos-mirror-android-signing-material | First-run Android keystore and private signing details, if generated |
| atomos-mirror-windows-signing-material | First-run Windows PFX and private signing details, if generated |

The signing-material artifacts are private credentials. Download them only from your own private repository, store them somewhere secure, and do not share them. They are retained for seven days by the workflow. The installable build artifacts are retained for 30 days.

Verify checksums after extracting the release artifact:

**Windows PowerShell:** run **Get-FileHash .\Atomos-Mirror-Setup-1.0.0.exe -Algorithm SHA256** and compare the result with the matching line in SHA256SUMS.txt.

## 3. Install on Android

1. Download Atomos-Mirror-Android-v1.0.0.apk to the phone.
2. Open the APK. If Android asks, allow the browser or file manager to install unknown apps for this installation.
3. Install and open **Atomos Mirror**.
4. Connect the phone to the same Wi-Fi network as the Windows PC.
5. In Atomos Mirror, start the receiver/hosting service.
6. Keep the app's displayed local URL and six-digit pairing PIN available. The URL normally resembles http://192.168.x.x:8765.
7. Do not share the PIN with devices you do not intend to pair.

The Android app hosts the web dashboard itself. It is not a website hosted on the public internet.

## 4. Install on Windows

1. Extract the Windows release artifact.
2. If this is the internal-testing build, import the accompanying atomos-codesign.cer certificate into the current Windows user's **Trusted Root Certification Authorities** and **Trusted Publishers** stores before launching the installer. Run these two commands in PowerShell:

   **Import-Certificate -FilePath .\atomos-codesign.cer -CertStoreLocation Cert:\CurrentUser\Root**

   **Import-Certificate -FilePath .\atomos-codesign.cer -CertStoreLocation Cert:\CurrentUser\TrustedPublisher**
3. Run Atomos-Mirror-Setup-1.0.0.exe.
4. If Windows Firewall prompts, allow Atomos Mirror on **Private networks**. Do not enable public-network access unless you deliberately need it.
5. Launch Atomos Mirror from the Start menu.

The first-run Windows certificate is self-signed. Importing it lets Windows validate this internal test publisher on this PC, but it does not create public certificate authority trust or guarantee that Microsoft SmartScreen will stop warning. For public distribution, replace it with an organisation-owned or publicly trusted Authenticode code-signing certificate.

## 5. Windows to Android: show the PC screen on the phone

1. Start the receiver in the Android app.
2. Open the local URL shown by Android in the phone's browser. Use the same phone that is hosting the receiver.
3. In the dashboard, enter the six-digit PIN shown in the Android app and select **Connect receiver**.
4. Open the Windows companion.
5. Discover the Android receiver, or enter its local IP address manually.
6. Enter the six-digit PIN from Android.
7. Choose the desktop-to-Android sending mode.
8. Select **Share desktop**, choose a monitor or individual window, and approve Windows' screen-capture prompt.
9. The desktop stream should appear in the Android browser dashboard. Use the desktop app's stop control to end sharing.

The browser dashboard and Windows sender are separate peers. The dashboard must be paired as the receiver before the Windows sender begins negotiation.

## 6. Android to Windows: show the phone screen on the PC

1. Start the receiver in the Android app.
2. Open the Windows companion.
3. Discover the Android receiver or enter its local IP address.
4. Enter the six-digit PIN displayed in the Android app.
5. Choose the Android-screen receiving mode in the Windows companion.
6. In the Android app, select the option to share the Android screen to Windows.
7. Approve Android's MediaProjection screen-capture prompt. Android displays its own screen-sharing indicator while capture is active.
8. The phone screen should appear in the Windows preview.
9. Stop sharing from Android when finished.

Android requires a fresh system screen-capture approval when starting a MediaProjection session. The app cannot silently capture the screen.

## 7. Connection diagnostics

The dashboard and desktop companion show WebRTC diagnostics when the platform exposes the relevant statistics:

- **Bitrate:** approximate current video throughput.
- **FPS:** approximate frames per second, calculated from frame counters when available.
- **Packet loss:** packets reported lost by WebRTC.
- **RTT:** round-trip time estimate where available.
- **Connection state:** WebRTC/ICE connection state and troubleshooting hints.

Metrics are sampled periodically. A blank or unavailable metric does not necessarily mean the stream has failed; browser and codec implementations expose different statistics.

## 8. Network and security model

| Port / layer | Purpose |
|---|---|
| TCP 8765 | Android-hosted dashboard and status API |
| TCP 8766 | WebSocket signalling between peers |
| WebRTC media | Direct peer-to-peer audio/video transport over the LAN |

The Android device coordinates pairing and relays WebRTC negotiation messages. It does **not** relay the video stream through the HTTP or WebSocket server. WebRTC media uses DTLS-SRTP encryption after negotiation. The local signalling connection currently uses plain ws://, so use a trusted private network.

The six-digit pairing PIN is not published in the status API or mDNS discovery records. Discovery finds the device address; the user enters the PIN manually. Signalling currently allows one sender and one receiver per session, validates message direction by peer role, and rate-limits repeated incorrect PIN attempts.

Do not use Atomos Mirror on an untrusted public Wi-Fi network. The PIN is a pairing control, not a substitute for LAN isolation or TLS-protected signalling.

## 9. Troubleshooting

**The Android device is not discovered**
- Confirm both devices are on the same Wi-Fi/LAN.
- Check that the Android receiver is running.
- Ensure the access point allows multicast/mDNS and client-to-client traffic.
- Enter the Android IP address manually if discovery is unavailable.
- VPNs, guest networks, mesh isolation and some mobile hotspots can prevent discovery or direct connections.

**The dashboard does not open**
- Confirm the receiver shows a local IP address and is ready.
- Open the full URL, including http:// and port 8765.
- Make sure the phone's browser is on the same phone/network as the receiver.
- If the displayed address belongs to a VPN or inactive adapter, reconnect Wi-Fi and restart the receiver.

**Pairing says the PIN is incorrect**
- Enter the current six-digit PIN shown in the Android app.
- Do not use an old PIN from a previous receiver session.
- After several incorrect attempts, wait for the temporary rate limit to expire.

**The devices pair but video does not start**
- Make sure the receiver peer joins before starting the sender.
- Stop and reconnect both sides, then start the sender again.
- Allow the app through Windows Defender Firewall on private networks.
- Disable VPNs temporarily and check router/AP client isolation.
- If the network blocks direct peer traffic, WebRTC cannot establish the media path. This build does not use a STUN/TURN relay.

**Video is choppy or delayed**
- Start with 720p or 1080p, 24 or 30 FPS, and a moderate bitrate.
- Prefer 5 GHz Wi-Fi or wired Ethernet for the PC.
- Reduce other traffic on the network and keep devices near the access point.
- Compare bitrate, FPS, packet loss and RTT diagnostics.

**The Windows installer shows a publisher warning**
- The internal test certificate is self-signed. Import the accompanying .cer into the current user's Trusted Root and Trusted Publishers stores.
- This is only appropriate for a certificate obtained from your own private release artifact.
- SmartScreen reputation warnings may remain. Public distribution needs a trusted Authenticode certificate.

**Android screen sharing stops**
- Keep the foreground screen-sharing notification active.
- Some Android vendors apply aggressive battery restrictions. Set Atomos Mirror to unrestricted battery use during testing if the OS is stopping its foreground service.
- Android internal playback audio capture is not implemented yet. Optional microphone capture is separate.

## 10. Known limitations before public production

- Android internal playback audio capture is not implemented. Optional microphone capture is available.
- Windows system-audio loopback depends on Windows/Electron support and may not work on every PC.
- No STUN/TURN relay is configured. Both devices need a LAN that permits direct peer connectivity.
- Signalling is local plain WebSocket rather than TLS.
- mDNS discovery requires multicast; manual IP entry is the fallback.
- Real-device interoperability, extended-session stability, battery behavior, audio quality, multiple Wi-Fi chipsets and Windows firewall configurations still require a proper test matrix.
- The Android release is signed, but the first-run test key is self-generated unless a persistent release keystore is configured.
- The first-run Windows installer is Authenticode-signed with a self-signed test certificate unless a persistent code-signing PFX is configured.

## 11. Preserve the signing identities

**Important:** Android will reject an in-place update if the new APK is signed with a different key. Keep the Android keystore and passwords backed up securely. Never regenerate them after distributing the APK.

The signed-release workflow supports these GitHub Actions repository secrets:

| Secret | Value |
|---|---|
| ANDROID_KEYSTORE_BASE64 | Base64 of the .keystore file |
| ANDROID_KEYSTORE_PASSWORD | Keystore password |
| ANDROID_KEY_ALIAS | Key alias |
| ANDROID_KEY_PASSWORD | Private-key password |
| WINDOWS_CERT_PFX_BASE64 | Base64 of the code-signing .pfx |
| WINDOWS_CERT_PASSWORD | PFX password |

For the first run, if these secrets are absent, the workflow generates temporary internal-testing signing identities and uploads them as separate seven-day signing-material artifacts. Download those artifacts and preserve the files. Add the values from their *-signing-secrets.txt files to **GitHub → repository Settings → Secrets and variables → Actions → New repository secret** before the next release build. The workflow then reuses those identities.

To create the base64 values locally in PowerShell, run one command at a time:

**[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\atomos-release.keystore")) | Set-Clipboard**

**[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\atomos-codesign.pfx")) | Set-Clipboard**

Paste each result into the matching GitHub secret. Never paste private keys or passwords into source files, commits, issues, or chat.

For a publicly distributed Windows application, use a trusted Authenticode certificate rather than the self-signed test certificate. Keep the PFX private and protect it with a strong password.

## 12. Build from source

### Android
Requirements: JDK 17, Android SDK 36, Gradle 8.13.

Run **gradle assembleDebug** for a debug build.

A signed release build requires the four Android signing environment variables used by app/build.gradle.kts:
- ATOMOS_SIGNING_STORE_FILE
- ATOMOS_SIGNING_STORE_PASSWORD
- ATOMOS_SIGNING_KEY_ALIAS
- ATOMOS_SIGNING_KEY_PASSWORD

Then run **gradle assembleRelease**.

### Windows
Requirements: Windows 10/11, Node.js 22, npm.

From the desktop directory, run:

- **npm install**
- **npm test**
- **npm start**
- **npm run dist**

A signed Windows build requires CSC_LINK to point to a PFX file and CSC_KEY_PASSWORD to contain its password. The signed-release GitHub Actions workflow configures these automatically.

## 13. Release workflow

The **Signed Release Candidate** workflow runs on pushes to main and can also be started manually from the Actions tab. It builds and verifies a signed Android release APK, runs desktop regression tests, builds the Authenticode-signed Windows installer, and uploads the outputs as versioned Actions artifacts.

The current 1.0.0 artifacts are internal test builds. Do not describe them as publicly trusted production releases until:
1. persistent signing identities are securely configured,
2. the Android and Windows devices complete the real-device test matrix,
3. the Windows installer is signed with a trusted publisher certificate if public distribution is intended, and
4. the remaining limitations above have been addressed or accepted.
