# AirPodsAdapt（AirPods 助手）

在 Android（尤其是小米 / HyperOS）上，获得接近 iPhone 的 AirPods 体验：精确电量、降噪模式切换、入耳检测、对话感知等——**无需 root**。

> 中文说明在下方；English version: [README.en.md](README.en.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

---

## 这是什么

Android 把 AirPods 当作普通蓝牙耳机，只能播放/暂停和基础的降噪切换。电量、入耳检测、长按设置、对话感知等功能都走 Apple 的私有协议 **AAP（Apple Accessory Protocol）**，系统不认。

AirPodsAdapt 直接用 AAP 和耳机通信，把这些功能带到 Android 上，并用一套 Jetpack Compose 界面呈现。

> 本项目在协议层面参考了开源项目 [LibrePods](https://github.com/kavishdevar/librepods) 和 [CAPod](https://github.com/d4rken-org/capod) 公开的逆向文档。所有代码为独立实现，不复制上述项目源码。详见 [致谢](#致谢) 与 [docs/how-it-works.md](docs/how-it-works.md)。

## 功能

| 功能 | 状态 |
|---|---|
| 精确电量（左 / 右 / 充电盒，1% 精度，含充电状态） | ✅ |
| 聆听模式切换（降噪 / 自适应 / 通透 / 关闭） | ✅ |
| 自适应强度调节 | ✅ |
| 入耳检测（主副耳状态） | ✅ |
| 摘下耳机自动暂停 / 戴回恢复（手机端实现） | ✅ |
| 对话感知自动降低媒体音量（手机端实现） | ✅ |
| 耳机设置：个性化音量、单耳降噪、音量轻扫、麦克风选择等 | ✅ |
| 按住耳机柄的模式组合、按压速度/时长 | ✅ |
| 重命名、读取型号 / 序列号 / 固件版本 | ✅ |
| 常驻通知（电量 + 模式按钮）、快捷设置磁贴、开盖电量弹窗 | ✅ |
| BLE 广播降级模式（控制通道不可用时的只读电量，10% 精度） | ✅ |
| 助听 / 自定义通透 / 降低响亮声音 | ⛔ 需要 root（VendorID hook） |
| 带头部追踪的空间音频、Apple 设备间切换、Siri、查找、固件升级 | ⛔ 依赖 Apple 生态，无法实现 |

当前在 **AirPods Pro 2** 上实测通过；AirPods Pro / 4 (ANC) / Pro 3 等支持 AAP 的型号理论上可用，欢迎反馈。

## 设备要求

AAP 跑在经典蓝牙的 **L2CAP** 通道上。能否连上取决于你的系统：

- **Android 17 / 基于 AOSP 17 的系统**：Google 在 2026-03 修复了蓝牙栈的 L2CAP 问题，预期开箱可用。
- **Android 16**：取决于厂商是否合入该修复。Pixel 在 2026-04 更新后可用。
- **小米 / HyperOS**：系统自带的 `com.xiaomi.bluetooth` 使用一套私有白名单机制；需要它先识别并连接 AirPods（把设备加入白名单），本应用再通过 AAP 的「接管连接」命令取得控制权。**无需停用该组件，也无需 root。** 详见 [docs/how-it-works.md](docs/how-it-works.md)。

> 兼容性因 ROM 而异。若在你的设备上成功或失败，欢迎提交 [设备兼容性报告](../../issues/new?template=device-report.md)。

## 安装

### 从源码构建

需要 JDK 17+、Android SDK（compileSdk 35）。

```bash
git clone <this-repo>
cd AirPodsAdapt
# 创建 local.properties 指向你的 Android SDK（或用 ANDROID_HOME 环境变量）
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
```

生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`，用 `adb install -r` 安装即可。

### 权限

首次启动会申请：
- **附近的设备 / 蓝牙**（连接耳机、扫描广播，不含位置用途）
- **通知**（常驻电量、开盖弹窗）

本应用**没有联网权限**，所有数据只在手机与耳机之间传输。

### 后台保活（小米 / HyperOS 必看）

为了息屏后仍能自动连接、自动暂停，请：
1. 允许本应用「忽略电池优化」（应用内有按钮）。
2. 在应用信息里开启「自启动」，并把「省电策略」设为「无限制」。

## 工作原理（简要）

```
┌─────────────┐   AAP over L2CAP (PSM 0x1001)   ┌──────────┐
│ AirPodsAdapt│  ←───────────────────────────→   │ AirPods  │
└─────────────┘   握手 / 通知订阅 / 控制命令        └──────────┘
      │
      └── 控制通道不可用时降级 → BLE 广播（只读，10% 精度）
```

- **协议层** `aap/`：AAP 包的构造与解析、L2CAP socket 的反射创建、连接与读循环。
- **BLE 层** `ble/`：解析 Apple Proximity 广播，作为降级的只读电量来源。
- **核心** `core/`：全局状态 `StateFlow`、控制入口、诊断日志。
- **服务** `service/`：前台服务（自动连接、重试、入耳暂停、对话降音量、通知）、磁贴、开机广播。
- **界面** `ui/`：Jetpack Compose 单 Activity。

完整的三层突破过程（反射限制 → 小米白名单 → 接管连接所有权）记录在 [docs/how-it-works.md](docs/how-it-works.md)。

## 免责声明

- 本项目与 **Apple**、**小米** 均无任何关联，也未获其认可。AirPods、AirPods Pro 是 Apple Inc. 的商标，仅用于描述兼容性。
- 本项目通过逆向工程实现互操作，仅供学习与个人使用。使用风险自负；对任何设备损坏或数据丢失不承担责任（见 [LICENSE](LICENSE) 第 15/16 条）。
- 功能依赖具体的耳机固件与系统 ROM，可能随更新失效。

## 致谢

- [LibrePods](https://github.com/kavishdevar/librepods)（GPL-3.0）与 [CAPod](https://github.com/d4rken-org/capod)：公开的 AAP 协议逆向文档。
- [LSPosed/AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)（Apache-2.0）：解除隐藏 API 反射限制。

## 贡献

欢迎提交设备兼容性报告、Bug、新型号适配。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[GNU GPL v3.0](LICENSE)。选择 GPL 是为了与所参考的 LibrePods 生态保持一致。
