# 工作原理 / How it works

本文记录 AirPodsAdapt 在无 root 的 Android（尤其小米 / HyperOS）上连接 AirPods 的完整过程。英文摘要见文末。

## 背景：AAP 与 L2CAP

AirPods 的高级功能走 Apple 私有协议 **AAP（Apple Accessory Protocol）**，它跑在经典蓝牙（BR/EDR）的 **L2CAP** 通道上，PSM 为 `0x1001`，服务 UUID 为 `74ec2172-0bad-4d01-8f77-997b2be0722a`。

业务包格式：

```
04 00 04 00 [opcode 低字节] [opcode 高字节] [data...]
```

常用：握手 `00 00 04 00 01 00 02 00 …`、特性标志 `04 00 04 00 4D 00 D7 …`、通知订阅 `04 00 04 00 0F 00 FF FF FF FF`、控制命令 `04 00 04 00 09 00 [id] [d1..d4]`。协议细节参考 LibrePods / CAPod 的公开文档。

## 三层障碍

在小米 15 Pro（HyperOS OS3.0.309，Android 16，无 root）上实测，连接 AirPods 要依次跨过三道坎。

### 第一层：隐藏 API 的反射限制

公开 API 只能建 RFCOMM 和 BLE 的 L2CAP，而 AAP 用的是**经典蓝牙的 L2CAP**，只能反射调用 `BluetoothSocket` 的非公开构造函数（`type = 3 / TYPE_L2CAP`）。

自 Android 9 起系统会拦截这种反射，日志表现为：

```
hiddenapi: Accessing hidden method Landroid/bluetooth/BluetoothSocket;-><init>(...) ... reflection: denied
```

**解法**：引入 [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)，只对 `BluetoothSocket` / `BluetoothDevice` 两个类解除限制：

```kotlin
HiddenApiBypass.addHiddenApiExemptions(
    "Landroid/bluetooth/BluetoothSocket;",
    "Landroid/bluetooth/BluetoothDevice;",
)
```

之后 socket 能正常创建。不同 Android 版本构造函数签名不同，需要按顺序尝试多种签名（见 `aap/L2capSocketFactory.kt`）。

### 第二层：小米的 L2CAP 白名单

socket 建好后，`connect()` 仍然超时。蓝牙栈日志：

```
btsock_l2cap_listen_or_connect: fcr_basic is_dev_in_white_list = false
l2c_fcr_chk_chan_modes: L2CAP - Peer does not support our desired channel types
```

原因：这台 ROM 没有 Google 2026-03 的 AOSP L2CAP 修复。小米改用自己的机制——系统属性 `persist.bluetooth.l2cap.connect.fcr.basic.mode.whitelist` 维护一份设备白名单，**只有名单内的设备才允许用 AirPods 需要的 basic 模式**（否则协商 ERTM 模式，被 AirPods 拒绝）。

该属性受 SELinux 限制（`bluetooth_prop` context），只有 bluetooth uid 能写，shell / 普通应用（即使有 root 也要绕 SELinux）都写不了。往名单里加设备的唯一途径是让 `com.xiaomi.bluetooth` 识别该 AirPods 型号并主动连接——它在 `DevicesTransportHandler` 里调用 `Utils.addToWhitelist` 完成。

**结论**：小米组件必须保持启用。实测在 HyperOS 上 `pm disable-user` 会被拒（`Cannot disable system packages`），`pm suspend` 也无法停止已在运行的系统服务——所以**无法无 root 地移除它**，也**不需要**移除它。

> 若型号未被识别，可触发一次：
> ```
> adb shell content call --uri content://com.android.bluetooth.ble.app.headsetdata.provider \
>   --method onAirPodsProductId --arg <MAC> --extra product_id:s:1420
> ```
> `1420` 为 AirPods Pro 2 的型号 ID。通常由系统自动完成，无需手动。

### 第三层：连接所有权（关键突破）

设备进入白名单后，本应用能连上 L2CAP、握手也有回应，但**收不到任何通知流**（电量、模式、入耳都没有）。因为 AirPods 只把通知发给一个「主控」host，而 `com.xiaomi.bluetooth` 抢先占住了这个角色——同一时间只有一个 App 能真正控制耳机。

**解法**：AAP 控制命令里有一条 `OWNS_CONNECTION`（标识 `0x06`）。连接后发送：

```
04 00 04 00 09 00 06 01 00 00 00
```

耳机随即把完整状态流切给本应用：电量、聆听模式、入耳、设备信息、各项设置一次性全部下发；此后本应用发出的控制命令（如切降噪）也能生效。实测接管后稳定，小米不会来回抢占；关闭本应用后小米自动收回。

```kotlin
// aap/AapConnection.kt，初始化序列末尾
send(AapProtocol.handshake())
send(AapProtocol.featureFlags())
send(AapProtocol.requestNotifications())
send(AapProtocol.control(ControlId.OWNS_CONNECTION, ON)) // 接管
```

## 降级路径：BLE 广播

当上述任一环节不可用（例如 ROM 根本不支持、或控制通道被占用无法接管）时，本应用降级为解析 AirPods 的 **BLE 广播**（Apple 厂商 ID `76`，类型 `0x07`）。广播包含电量和入耳状态，但电量精度仅 10%（4 bit 一档），且**只读**。实现见 `ble/ProximityParser.kt`。

## 旁路：小米 ContentProvider（备用只读源）

`com.xiaomi.bluetooth` 把解出的状态通过一个 ContentProvider 暴露，且 `getAirpodsState` 等方法对任意应用可调：

```
adb shell content call --uri content://com.android.bluetooth.ble.app.headsetdata.provider \
  --method getAirpodsState --arg <MAC>
# → modelName / leftBattery / rightBattery / boxBattery / isLeftWearing / ... （1% 精度）
```

本应用默认不依赖它（优先走自己的 AAP 通道，功能更全），但它是一个可靠的只读电量/佩戴来源，可作为将来的备选。

## 兼容性小结

| 系统 | 能否连 | 说明 |
|---|---|---|
| Android 17 / AOSP 17 | 预期可以 | AOSP 已含 L2CAP 修复 |
| Pixel Android 16（2026-04+） | 可以 | Pixel 合入了修复 |
| 小米 / HyperOS | 可以 | 需系统组件先把设备加入白名单，再由本应用接管 |
| 其他未合入修复的 Android 16 | 否 | 直连 L2CAP 会被 ERTM 协商拒绝 |

---

## English summary

Connecting to AirPods' AAP control channel (Bluetooth Classic L2CAP, PSM `0x1001`) on non‑rooted Android requires clearing three layers:

1. **Hidden‑API reflection** — AAP needs a *Classic* L2CAP `BluetoothSocket`, only reachable via a non‑public constructor (`type = 3`). Android ≥ 9 blocks the reflection; we lift it for `BluetoothSocket`/`BluetoothDevice` only, using AndroidHiddenApiBypass.
2. **Xiaomi's L2CAP allow‑list** — HyperOS builds without Google's 2026‑03 AOSP fix gate basic‑mode L2CAP behind `persist.bluetooth.l2cap.connect.fcr.basic.mode.whitelist`, writable only by the bluetooth uid. Only `com.xiaomi.bluetooth` adds a recognized AirPods to it. It can't be disabled without root on HyperOS, and doesn't need to be.
3. **Connection ownership** — AirPods stream notifications to a single owner; Xiaomi grabs it first. Sending the AAP `OWNS_CONNECTION` (`0x06 = 1`) control command hands the full stream and control authority to this app. No root.

Fallbacks: parse AirPods' BLE advertisements (Apple company ID `76`, type `0x07`) for read‑only 10%‑step battery; or read Xiaomi's `getAirpodsState` ContentProvider method (callable by any app) for 1%‑step battery/wear.
