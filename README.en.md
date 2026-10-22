# AirPodsAdapt

Get a near‑iPhone AirPods experience on Android (especially Xiaomi / HyperOS): accurate battery, noise‑control switching, in‑ear detection, conversation awareness and more — **no root required**.

> 中文说明：[README.md](README.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

---

## What is this

Android treats AirPods as ordinary Bluetooth headphones — only play/pause and basic noise‑control work. Battery, in‑ear detection, stem configuration, conversation awareness and other features ride on Apple's proprietary **AAP (Apple Accessory Protocol)**, which Android doesn't speak.

AirPodsAdapt talks AAP directly to the earbuds and surfaces those features through a Jetpack Compose UI.

> The protocol layer is based on the publicly documented reverse‑engineering of [LibrePods](https://github.com/kavishdevar/librepods) and [CAPod](https://github.com/d4rken-org/capod). All code here is an independent implementation and does not copy their sources. See [Acknowledgments](#acknowledgments) and [docs/how-it-works.md](docs/how-it-works.md).

## Features

| Feature | Status |
|---|---|
| Accurate battery (left / right / case, 1% steps, with charging state) | ✅ |
| Listening‑mode switching (ANC / Adaptive / Transparency / Off) | ✅ |
| Adaptive strength | ✅ |
| In‑ear detection (primary / secondary) | ✅ |
| Auto‑pause on removal / resume on re‑insert (phone‑side) | ✅ |
| Conversation awareness auto media ducking (phone‑side) | ✅ |
| Earbud settings: personalized volume, one‑bud ANC, volume swipe, mic selection, … | ✅ |
| Press‑and‑hold mode set, click speed / hold duration | ✅ |
| Rename, read model / serial / firmware | ✅ |
| Persistent notification (battery + mode buttons), QS tile, lid‑open popup | ✅ |
| BLE advertisement fallback (read‑only battery, 10% steps, when the control channel is unavailable) | ✅ |
| Hearing aid / custom transparency / loud‑sound reduction | ⛔ needs root (VendorID hook) |
| Spatial audio with head tracking, cross‑Apple‑device handoff, Siri, Find My, firmware update | ⛔ requires Apple's ecosystem |

Verified on **AirPods Pro 2**. Other AAP‑capable models (AirPods Pro / 4 (ANC) / Pro 3, …) should work in theory — reports welcome.

## Device requirements

AAP runs over a Bluetooth Classic **L2CAP** channel. Whether it connects depends on your OS:

- **Android 17 / AOSP‑17‑based ROMs**: Google fixed the Bluetooth stack's L2CAP issue in the 2026‑03 release; expected to work out of the box.
- **Android 16**: depends on whether the OEM rebased that fix. Pixel got it in the 2026‑04 update.
- **Xiaomi / HyperOS**: the stock `com.xiaomi.bluetooth` uses a private allow‑list mechanism; it must recognize and connect to the AirPods first (adding the device to the allow‑list), after which this app takes control via the AAP "owns connection" command. **No need to disable that component, and no root.** See [docs/how-it-works.md](docs/how-it-works.md).

> Compatibility varies by ROM. If it works or fails on your device, please file a [device report](../../issues/new?template=device-report.md).

## Install

### Build from source

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
git clone <this-repo>
cd AirPodsAdapt
# point local.properties at your Android SDK (or use ANDROID_HOME)
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`; install with `adb install -r`.

### Permissions

On first launch it requests:
- **Nearby devices / Bluetooth** (connect to the earbuds, scan advertisements; not used for location)
- **Notifications** (persistent battery, lid popup)

The app has **no network permission**; all data stays between phone and earbuds.

### Keeping it alive in the background (Xiaomi / HyperOS)

So that auto‑connect and auto‑pause keep working after the screen turns off:
1. Allow the app to ignore battery optimization (there's an in‑app button).
2. In App info, enable Autostart and set the battery policy to No restrictions.

## How it works (short)

```
┌─────────────┐   AAP over L2CAP (PSM 0x1001)   ┌──────────┐
│ AirPodsAdapt│  ←───────────────────────────→   │ AirPods  │
└─────────────┘   handshake / notify / control     └──────────┘
      │
      └── control channel unavailable → BLE advertisement (read‑only, 10%)
```

- **Protocol** `aap/`: build/parse AAP packets, reflective L2CAP socket creation, connect & read loop.
- **BLE** `ble/`: parse Apple Proximity advertisements as a read‑only battery fallback.
- **Core** `core/`: global `StateFlow` state, control entry point, diagnostic log.
- **Service** `service/`: foreground service (auto‑connect, retry, ear auto‑pause, conversation ducking, notification), QS tile, boot receiver.
- **UI** `ui/`: single‑Activity Jetpack Compose.

The full three‑layer breakthrough (hidden‑API reflection → Xiaomi allow‑list → owning the connection) is written up in [docs/how-it-works.md](docs/how-it-works.md).

## Disclaimer

- This project is **not affiliated with or endorsed by Apple or Xiaomi**. AirPods and AirPods Pro are trademarks of Apple Inc., used only to describe compatibility.
- It achieves interoperability through reverse engineering, for learning and personal use. Use at your own risk; no liability for device damage or data loss (see sections 15/16 of the [LICENSE](LICENSE)).
- Functionality depends on specific earbud firmware and ROM, and may break with updates.

## Acknowledgments

- [LibrePods](https://github.com/kavishdevar/librepods) (GPL‑3.0) and [CAPod](https://github.com/d4rken-org/capod): public AAP reverse‑engineering docs.
- [LSPosed/AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache‑2.0): lifting hidden‑API reflection restrictions.

## Contributing

Device reports, bugs and new‑model support are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## License

[GNU GPL v3.0](LICENSE). GPL was chosen to stay consistent with the LibrePods ecosystem this project references.
