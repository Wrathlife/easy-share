# Netshare

Device-to-device file sharing **over the internet** — **no account**. Pairing uses a short code online; **file bytes stay peer-to-peer** when WebRTC connects (MQTT AES path when Encrypt is on or ICE fails). Signaling uses a public MQTT broker (`broker.emqx.io`) with AES-GCM sealed payloads.

Android and Windows clients speak the same wire protocol (see [`docs/PROTOCOL.md`](docs/PROTOCOL.md)).

## Build — Android

Open in Android Studio or:

```bat
gradlew.bat :app:assembleDebug
```

Requires Android SDK + JDK 17.

### Unity Ads (optional local overrides)

Defaults are baked from `local.properties` (see `local.properties.example`):

```properties
unity.ads.gameId=800275522
unity.ads.interstitialPlacementId=Interstitial_Android
unity.ads.bannerPlacementId=Banner_Android
```

- Banner on Home
- Interstitial once after a successful share/receive transfer
- Debug builds use Unity test mode
- Home **Remove ads** uses Play Billing product `remove_ads` (override with `billing.removeAdsProductId`)

### Remove ads (Play Console)

1. Create app `com.netshare.app` (use a Play upload; internal testing is enough)
2. Monetize → In-app products → one-time product ID **`remove_ads`** (non-consumable / managed product)
3. Activate the product, then install a build signed with the upload key (or license testers on debug if linked)

## Build — Windows

Requires [.NET 8 SDK](https://dotnet.microsoft.com/download). **No ads** on the desktop client.

```bat
cd windows
publish.bat
```

Or:

```bat
dotnet publish src\EasyShare.Desktop\EasyShare.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o artifacts
```

Run `windows\artifacts\Netshare.exe`.

### Android ↔ Windows test matrix

| Mode | What to do |
|------|------------|
| WebRTC (default) | Encrypt **off** on both sides; confirm matching phrase; transfer P2P |
| MQTT encrypt | Check **Encrypt via MQTT** on Windows and Encrypt on Android |
| Fallback | If WebRTC fails (~18s), host announces MQTT and transfer continues |

## Repo

Personal project under [Wrathlife/easy-share](https://github.com/Wrathlife/easy-share).
