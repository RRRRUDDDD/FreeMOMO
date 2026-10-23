# FreeMOMO Rootless（免 root 分支）

> **仅供学习与研究。** 本分支不提供任何 APK、原始安装包、签名密钥、成品包或构建产物。你需要自行获取官方 APK、自行构建、自行签名，并自行承担全部后果。请支持正版。

FreeMOMO Rootless 是 FreeMOMO 的免 root 实验线，不依赖 root、LSPosed、Zygisk 或 LSPatch 管理器。需要 LSPosed + Zygisk 的稳定版本请使用 [`main` 分支](https://github.com/RRRRUDDDD/FreeMOMO/tree/main)。

它不是一个可以直接安装的 Xposed 模块，而是重打包后的墨墨本体加一个同包名 feature split。FreeMOMO 模块与纯 Java ART hook 引擎放在 split 中，由系统在安装时融合进墨墨的 ClassLoader。

- 适配：墨墨背单词 5.6.00（versionCode 900）
- 架构：arm64-v8a，4 KB 内核页

## 工作原理

1. **base**：官方 APK 的原有条目、偏移和字节保持不变，仅重新签名，并把 `AndroidManifest.xml` 中的 `appComponentFactory` 等长改名为 split 提供的类。
2. **split**：`classes.dex` 是纯 Java ART entrypoint 引擎、桥表与启动门，`classes2.dex` 是 FreeMOMO 模块 dex。
3. 安装时系统把 split 融合进 base 的 ClassLoader；工厂类在 `<clinit>` 中启动引擎并分发模块入口。

## 你需要准备

- 官方 `墨墨背单词 5.6.00` APK（versionCode 900）。构建脚本会校验固定 SHA-256，不匹配会直接拒绝。
- JDK 21
- Android SDK：platform 36，build-tools 需包含 `aapt2`、`d8`、`apksigner`、`zipalign`
- Python 3.9+（仅标准库）
- 网络（首次构建会下载 `hiddenapibypass-6.1.aar`；也可用 `--hidden-api-aar` 指定本地文件）

签名密钥不需要事先准备：首次运行会在 `build/rootless/signing/` 下生成 PKCS12 密钥与密码文件。**请备份整个 `signing/` 目录**，以后覆盖更新必须使用同一密钥。也可以传入自己的密钥：

```text
--keystore <path> --keystore-password-file <path> [--key-alias <alias>]
```

或通过环境变量 `FREEMOMO_KEYSTORE_PASSWORD` 提供密码。密钥库中的别名不是 `freemomo` 时，用 `--key-alias` 指定。

## 构建

在仓库根目录先构建模块：

```powershell
.\gradlew.bat :app:assembleRelease
```

Linux / macOS：

```sh
./gradlew :app:assembleRelease
```

然后构建 base + split：

```powershell
python rootless\build_rootless.py --original path\to\墨墨背单词_5.6.00.apk
```

Linux / macOS：

```sh
python3 rootless/build_rootless.py --original path/to/墨墨背单词_5.6.00.apk
```

如果原包就在仓库根目录并命名为 `墨墨背单词_5.6.00.apk`，可以省略 `--original`。脚本会自动寻找 `app/build/outputs/apk/release/` 下的模块 APK，也支持 `--module-apk` 指定。

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--original` | 官方 5.6.00 APK；也可用 `FREEMOMO_ORIGINAL` |
| `--module-apk` | 模块 APK；默认自动查找 |
| `--base-apk` | 复用已有的 base（必须与 split 使用同一签名密钥与包版本） |
| `--hidden-api-aar` | 本地 `hiddenapibypass-6.1.aar` |
| `--keystore` / `--keystore-password-file` / `--key-alias` | 使用自己的签名密钥 |
| `--sdk` / `--jdk` / `--build-tools` | 指定工具链；默认读 `ANDROID_HOME` / `JAVA_HOME` |
| `--out` | 输出目录，默认 `build/rootless` |
| `--probe` | 额外构建独立自检 APK |
| `--no-bundle` | 不生成 SAI 整包 |

## 产物

```text
build/rootless/
├─ artifacts/
│  ├─ base.apk
│  ├─ freemomo-rootless-3.4-split.apk
│  ├─ freemomo-rootless-3.4.apks        # base + split 的 SAI 整包
│  ├─ freemomo-rootless-3.4-probe.apk   # 仅 --probe
│  └─ SHA256SUMS.txt
├─ base.apk                             # base 缓存
├─ deps/                                # 第三方依赖缓存
├─ logs/                                # 构建与签名日志
├─ signing/                             # 本地生成的签名密钥（勿提交）
└─ work/                                # 中间文件
```

## 安装

请使用**同一轮构建**的 base 与 split：

```powershell
adb install-multiple -r `
  build\rootless\artifacts\base.apk `
  build\rootless\artifacts\freemomo-rootless-3.4-split.apk
```

也可以把 `freemomo-rootless-3.4.apks` 交给 SAI 等支持 split 安装的工具。

首次启动请停留在墨墨窗口：启动页会等待模块与桥接完成，期间不要强行结束进程。

## 首次启动与排障

启动期关键日志：

```text
splash gate installed
delivery gate open
arm b_word
business-ready
splash gate open: module ready
```

- 若停在启动页：说明模块或桥接没有完成，启动门会保持关闭（fail-closed），不会进入未 hook 的主页。
- 闪退取证优先看 `adb logcat -b crash` 与 `dumpsys activity exit-info com.maimemo.android.momo`。
- 日志 tag 为 `FreeMOMO-Rootless`。

## 目录

| 路径 | 说明 |
| --- | --- |
| `app/` | FreeMOMO 模块源码；构建出的模块 dex 会融合进 split |
| `rootless/build_rootless.py` | base + split 构建入口 |
| `rootless/custom_repack.py` | 保持原布局的追加式重打包与签名 |
| `rootless/engine/` | 纯 Java ART entrypoint 引擎、桥表、启动门与 Xposed shim |
| `rootless/probe/` | 独立自检 App（`--probe`） |
| `rootless/verify_artifacts.py` | 固定输入常量与校验辅助 |
| `rootless/dependencies.json` | 原包与 hiddenapibypass 的固定摘要 |
| `native-jni/`、`zygisk-companion/` | `main` 分支的 root 方案，rootless 构建不使用 |

## 许可证

本项目基于 [MIT License](./LICENSE) 开源。

## 免责声明

本项目仅供学习和研究使用，请勿用于商业或非法用途。使用本项目产生的一切后果由使用者自行承担，本项目不提供任何明示或暗示的适配性、安全性或合法性担保。
