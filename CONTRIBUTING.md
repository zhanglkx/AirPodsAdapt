# 贡献指南 / Contributing

感谢你愿意参与！/ Thanks for your interest!

## 最有价值的贡献：设备兼容性报告

这类项目能否工作高度依赖具体 ROM。请提交 [设备兼容性报告](../../issues/new?template=device-report.md)，无论成功或失败，并附上：

- 机型、Android 版本、**完整 Build 号**（设置 → 关于手机 → 版本号）
- 耳机型号与固件版本
- 高级功能（降噪切换、入耳检测）是否可用
- 诊断页「复制全部」导出的日志（会包含系统信息与协议收发包）

## Bug 报告

请走 [Bug 模板](../../issues/new?template=bug_report.md)，附复现步骤与诊断日志。

## 代码贡献

1. 先开 issue 讨论方向，避免白做。
2. 遵循现有代码风格（Kotlin 官方风格，`kotlin.code.style=official`）；注释讲清「为什么」。
3. 不要提交：`local.properties`、密钥 / keystore、任何真实设备 MAC 地址或个人信息。
4. 提交前请确保通过：

   ```bash
   ./gradlew assembleDebug lintDebug
   ```

5. 新增源文件请保留顶部的 SPDX 许可证头：

   ```kotlin
   // SPDX-License-Identifier: GPL-3.0-or-later
   ```

6. PR 标题简洁（<70 字符），描述里写清改了什么、怎么测的。

## 协议相关

新增或修正 AAP 包格式时，请在 [docs/how-it-works.md](docs/how-it-works.md) 或代码注释里注明依据（抓包、参考文档等），便于他人核对。本项目仅参考 LibrePods / CAPod 的**公开文档**，请勿直接复制其 GPL 源码。

## 许可证

贡献的代码将以 [GPL-3.0-or-later](LICENSE) 授权。
