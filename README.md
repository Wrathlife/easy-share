# Easy Share

Peer-to-peer file sharing between devices — **no account, no cloud relay, no port forwarding**.

Android v1 uses WebRTC DataChannels with double-QR Offer/Answer signaling and public STUN only. Adaptive connect retries (LAN → WAN → IPv6 → TCP ICE → …) raise success rate; some symmetric-NAT pairs can still fail without TURN.

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
