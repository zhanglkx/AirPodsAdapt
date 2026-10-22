# 发布流程 / Releasing

本项目用 **GitHub Actions** 自动出包：你推送一个版本 tag（如 `v0.1.0`），CI 自动构建签名好的 APK，并创建对应的 Release 挂上产物。工作流见 [`.github/workflows/release.yml`](../.github/workflows/release.yml)。

## 一次性准备：生成发布密钥

APK 必须签名才能安装；而且**同一个 App 的后续更新必须用同一个密钥**，所以这个 keystore 要长期妥善保管、不要丢、不要进仓库。

生成（密码换成你自己的，别用示例）：

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias airpodsadapt \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '你的storepass' -keypass '你的keypass' \
  -dname "CN=AirPodsAdapt"
```

> `release.keystore`、`keystore.properties` 已在 `.gitignore` 里，不会被提交。

## 把密钥配成 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret，加 4 个：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64：`base64 -i release.keystore \| pbcopy`（macOS），把剪贴板内容粘进去 |
| `KEYSTORE_PASSWORD` | 上面的 storepass |
| `KEY_ALIAS` | `airpodsadapt` |
| `KEY_PASSWORD` | 上面的 keypass |

> 没配这些 secret 也能跑：CI 会回退到 debug 签名，产出的 APK 仍可安装，但不适合长期分发（debug 密钥是公开共享的，且无法作为后续更新的升级密钥）。

## 发一个版本

1. 改版本号：`app/build.gradle.kts` 里的 `versionCode`（每次 +1）和 `versionName`（如 `0.1.0`）。
2. 更新 [CHANGELOG.md](../CHANGELOG.md)。
3. 打 tag 并推送：

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

4. 打开仓库 Actions 页看 `Release` 工作流跑完，Releases 页就会出现 `v0.1.0`，APK 名为 `AirPodsAdapt-v0.1.0.apk`，发布说明由 commit 自动生成。

也可以在 Actions 页用 “Run workflow”（`workflow_dispatch`）手动触发。

## 本地手动出包（可选）

不想用 CI，想本地出一个签名包：

```bash
# 在项目根目录放一个 keystore.properties（已 gitignore）：
cat > keystore.properties <<'EOF'
storeFile=/绝对路径/release.keystore
storePassword=你的storepass
keyAlias=airpodsadapt
keyPassword=你的keypass
EOF

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

然后在 GitHub 网页手动 New release、拖上传 APK 即可。

## 注意

- Release 页的 APK 任何人可下载安装，请在发布说明里保留 [README](../README.md) 的免责声明与系统要求（尤其 HyperOS 的前提）。
- 丢了 keystore＝无法再发布能覆盖升级的新版本，务必备份。
