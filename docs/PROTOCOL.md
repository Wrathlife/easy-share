# Easy Share wire protocol (draft)

Platform-neutral contracts. Do **not** put Android `content://` URIs on the wire.

## QR signal bundle (v1)

Fields (binary → compressed → Base45/Base64 in QR):

- `v` — protocol version
- `role` — offer | answer
- `sessionId` — 16 bytes
- `auth` — 16 bytes shared secret
- `strategyId` — connect strategy
- `sdp` — completed non-trickle SDP + ICE
- `diag` — compact network hints (vpn, wifi/cell, ipv6, lanFingerprint)
- `exp` — expiry epoch seconds
- HMAC over fields using `auth`

Handshake (single code): host shows share code → guest enters it → devices pair.
No reply code. QR is an optional alternate for the same payload.

## DataChannel frames

Prefix: 1 byte `FrameType`, then payload.

| Type | Code | Purpose |
|------|------|---------|
| HELLO | 1 | version, device name, auth proof |
| MANIFEST | 2 | relative paths, sizes, sha256 |
| LIST_OK | 3 | guest ready |
| GET | 4 | path, offset, length |
| CHUNK | 5 | file id, offset, bytes |
| ACK / NACK | 6 / 7 | flow control |
| DONE / CANCEL | 8 / 9 | teardown |

## Manifest paths

Relative POSIX-style paths only, e.g. `photos/IMG_001.jpg`.
