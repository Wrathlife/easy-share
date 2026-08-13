# Netshare wire protocol (live)

Platform-neutral contracts. Do **not** put Android `content://` URIs on the wire.
Relative POSIX-style paths only (e.g. `photos/IMG_001.jpg`), max 180 chars, no `..` / absolute / `://`.

## Pairing code

- Format: 5 letters + 5 digits (`ABCDF23457` wire / `ABCDF-23457` display)
- Letters: `ABCDEFGHJKLMNPQRSTUVWXYZ` (no I/O); digits: `23456789` (no 0/1)
- Topic: `easyshare/v1/{sha256(code)[:32]}`
- Broker: `ssl://broker.emqx.io:8883` (MQTT 3.1.1)
- Session TTL: 10 minutes

## Crypto

1. Salt = `SHA-256("easyshare-v1-salt|" + code)`
2. Master = `PBKDF2-HMAC-SHA256(password=code as UTF-16BE chars, salt, 120000, 256 bits)`
3. `auth` = `HMAC-SHA256(master, "easyshare-v1-auth")`
4. `enc` = `HMAC-SHA256(master, "easyshare-v1-enc")`
5. Outer MQTT payload: `{"v":1,"blob":"<Base64(IV[12]||ciphertext+tag)>"}` AES-256-GCM
6. Inner JSON MAC: HMAC-SHA256 hex over `role|event|ts|exp|nonce|extra`
7. Confirm phrase: `SHA-256("easyshare-v1-confirm|" + code)` → two words from fixed list joined with ` · `

## Signaling events

Common fields: `r` (`h`/`g`), `e`, `ts`, `exp`, `nonce`, `mac`

| Event | Direction | Notes |
|-------|-----------|--------|
| `ready` | host | session up |
| `join` | guest | retry ~2s until paired |
| `paired` | host | after first join; enter confirm |
| `confirm` | either | both must confirm phrase |
| `manifest` | host | `files:[{n,s},…]`; MAC extra = files JSON array string |
| `sdp-offer` / `sdp-answer` | host / guest | MAC extra = `shortHash(sdp)` |
| `ice` / `ice-done` | either | MAC extra for ice = `shortHash(candidate)` |
| `xfer-mqtt` | host | force MQTT byte path |
| `fstart` / `fbin` / `fdone` | host | MQTT AES chunks; MAC extra `path\|size\|seq\|digest` |
| `xfer-complete` | host | MQTT transfer done |

## Transfer modes

1. **Default:** WebRTC DataChannel label `easyshare` (ordered). STUN: Google + Cloudflare. Trickle ICE over MQTT. ~18s budget then host publishes `xfer-mqtt`.
2. **Encrypt ON / fallback:** MQTT AES `fstart` → `fbin` → `fdone` → `xfer-complete`.

### DataChannel frames

Header: `type:u8` + `len:u32 BE` + payload.

| Type | Code |
|------|------|
| HELLO | 1 (`version=1`) |
| FILE_BEGIN | 2 |
| READY | 3 |
| CHUNK | 5 (64 KiB) |
| FILE_DONE | 8 |
| XFER_DONE | 9 |
| XFER_ACK | 10 |

Flow: guest READY ↔ host HELLO → FILE_BEGIN/CHUNK/FILE_DONE… → XFER_DONE → XFER_ACK.

## Clients

- Android: `app/` (Compose)
- Windows: `windows/` (.NET 8 WPF, WebRTC via SIPSorcery + MQTT via MQTTnet)
