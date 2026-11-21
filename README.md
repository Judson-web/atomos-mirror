# Atomos Mirror

**Local Wi-Fi screen mirroring for Android and browser receivers.**

The receiver dashboard is served directly by the Android device. It is not a hosted website and does not require a cloud account.

## Current foundation (0.1.0)

- Native Android application built with Kotlin and Jetpack Compose.
- On-device HTTP receiver dashboard powered by NanoHTTPD.
- Local network address display and JSON status endpoint at /api/status.
- Browser dashboard with receiver status and connection-list placeholder.
- No account or internet dependency for the local dashboard.

## Architecture

- Android sender/host: Kotlin, Jetpack Compose, MediaProjection and MediaCodec.
- Local receiver web UI: bundled HTML served by the Android app.
- Discovery: Android NSD/mDNS (next integration).
- Media transport: WebRTC (next integration).
- Audio: Android playback capture where source apps permit it, plus microphone capture with explicit permission.

## Run

Open the project in Android Studio with JDK 17 and Android SDK 36 installed. Build and run the app module on an Android 8.0+ device.

Start Browser receiver in the app, then open the displayed http://<device-lan-ip>:8765 address on another device connected to the same Wi-Fi network.

## Implementation status

This is the first application foundation, not a finished mirroring release. The local web dashboard and HTTP status endpoint are implemented. Device discovery, secure pairing, WebRTC negotiation, actual screen capture/encoding, audio capture/mixing, and receiver playback remain to be implemented and tested before claiming live mirroring.

## Platform notes

Android requires user approval for MediaProjection screen capture. Internal playback capture is subject to Android version requirements and the source application's capture policy. Microphone capture requires runtime permission. Router client isolation, VPNs, and firewall rules can prevent peer connectivity.
