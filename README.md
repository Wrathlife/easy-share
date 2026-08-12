# Easy Share

Device-to-device file sharing — **no account**. Pairing uses a short code over the internet; **file bytes stay peer-to-peer** (not relayed through a server). Early builds still use a public MQTT broker (`broker.emqx.io`) for **encrypted** pairing signaling only — payloads are AES-GCM sealed; the broker can still see topic timing/presence metadata. SPKI pins (leaf + intermediate) live in `MqttSsl` and `network_security_config.xml`; rotate them when the broker cert chain changes.

Android v1 targets WebRTC DataChannels with optional QR and public STUN. Adaptive connect retries (LAN → WAN → IPv6 → TCP ICE → …) raise success rate; some symmetric-NAT pairs can still fail without TURN.

## Status

Scaffold is in place:

- Compose UI: Home, Share/Receive placeholders, transfer progress preview
- `SessionUiState` + dual progress bars (overall + current file, speed/ETA)
- Package stubs: `webrtc`, `connect`, `transfer`, `files`, foreground service
- Platform-neutral wire contracts (relative paths only) for future iOS/Windows

Next: QR signal codec → WebRTC session → adaptive connect → SAF + real transfers.

## Build

Open in Android Studio or:

```bat
gradlew.bat :app:assembleDebug
```

Requires Android SDK + JDK 17.

## Repo

Personal project under [Wrathlife/easy-share](https://github.com/Wrathlife/easy-share).
