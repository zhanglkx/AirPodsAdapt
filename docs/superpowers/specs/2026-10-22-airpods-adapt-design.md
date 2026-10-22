# AirPodsAdapt 设计

目标：在小米 15 Pro（HyperOS）上用 AirPods Pro 2，获得接近 iPhone 的电量查看、模式切换与设置能力。无 root。

## 通信路径

| 路径 | 作用 | 前提 |
|---|---|---|
| AAP over L2CAP（PSM 0x1001） | 精确电量、入耳、降噪模式、对话感知、各项设置、改名、设备信息 | ROM 含 2026-03 AOSP L2CAP 修复；HyperOS 上 `com.xiaomi.bluetooth` 不占用该通道 |
| BLE 广播（Apple 厂商 ID 76，类型 0x07） | 10% 精度电量、开盖、入耳 | 仅需 BLUETOOTH_SCAN |

AAP 不可用时自动降级为 BLE 只读，并在 UI 给出原因与排查步骤。不自动停用任何系统组件。

## 模块

- `aap/AapProtocol`：常量与发包构造（握手、特性标志、通知注册、控制命令 `09`、改名 `1A`）。纯函数。
- `aap/AapParser`：收包 → `AapEvent`（电量 `04`、入耳 `06`、控制命令 `09`、对话感知 `4B`、设备信息 `1D`、未知）。纯函数。
- `aap/L2capSocketFactory`：反射调用 `BluetoothSocket` 隐藏构造函数（多版本签名依次尝试）。
- `aap/AapConnection`：连接、初始化序列、读循环、串行写。
- `ble/ProximityParser` + `ble/BleScanner`：广播解析与扫描，取信号最强的近距离 AirPods。
- `core/AirPodsState` + `core/AirPodsHub`：单例 `StateFlow` 状态源、控制入口、诊断日志环形缓冲。
- `service/AirPodsService`：前台服务（connectedDevice）。监听蓝牙连接事件 → 自动 AAP 连接，失败退避重试 3 次；入耳检测自动暂停/恢复；对话感知降低媒体音量；常驻通知（电量 + 模式按钮）；开盖 heads-up 通知。
- `service/NoiseControlTileService`：快捷设置磁贴，循环切换模式。
- `service/BootReceiver`：开机/更新后、蓝牙 ACL 连接时拉起服务。
- `ui/`：Compose 单 Activity。权限引导 → 主页（电量、模式、设置、改名、设备信息、后台保活引导）→ 诊断页（日志、系统信息、复制）。

## 功能范围

做：电量、模式切换（关闭/降噪/通透/自适应）、自适应强度、对话感知、个性化音量、耳机端入耳检测开关、App 端摘下暂停、单耳降噪、音量轻扫、按住循环的模式组合、按压速度、按住时长、麦克风选择、改名、设备信息、通知栏、磁贴、开盖通知。

不做：助听/自定义通透/降低响亮声音（需 root 改 VendorID）、头部追踪空间音频、苹果设备间切换、Siri、固件升级、头部手势。

## 错误处理

- 构造 socket 失败 / 连接超时（5s）/ 读写异常：记录诊断日志，状态置为 BLE 降级，并记录失败原因。
- 连接失败时 UI 显示排查卡：Android 版本、`com.xiaomi.bluetooth` 启用状态、对应操作说明（由用户决定是否停用）。
- Off 模式需先发送 `34 01`（允许关闭选项）。

## 验证

`./gradlew assembleDebug lintDebug` 通过；真机安装后看 logcat（tag `AirPodsAdapt`）确认 L2CAP 连接与收包。

## 环境

AGP 8.8.1、Gradle 8.10.2、Kotlin 2.0.21、compileSdk/targetSdk 35、minSdk 31、JDK 21（`/opt/homebrew/opt/openjdk@21`）。
