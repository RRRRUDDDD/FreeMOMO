# FreeMOMO

FreeMOMO 是一个配合 LSPosed 与 Zygisk 使用的墨墨背单词增强模块，用于修改单词上限、用户等级和相关权限。

**已适配：墨墨背单词 5.6.00（versionCode 900）**

内置映射版本直接安装 Java 层 Hook，无需等待结构扫描。其他版本会尝试根据运行时结构自动适配；如果某项结构无法唯一确认，该项会安全关闭。native 层仍需匹配已验证指纹，不能自动迁移。

## 使用前准备

使用前请先安装并启用：

- LSPosed
- Zygisk Next

> **注意：首次启动时需要等待 LSPosed Hook 和 Zygisk native 模块完成初始化。请在 App 中停留的时间久一点，不要刚进入墨墨就立即退出。**

## 为什么需要 Zygisk 模块

墨墨背单词 5.5.30 及后续版本的部分检测已经移动到 native 层，单独使用 Java 层 Hook 无法修改这部分逻辑。

- LSPosed 模块负责 Java 层的单词上限、等级和权限 Hook。
- Zygisk 模块负责在进程早期处理 native 层的 SecNeo 检测。

两部分职责不同，缺少 Zygisk 模块时，Java Hook 无法替代 native 层处理。

当前 native 适配只支持 4096 字节内核页，保留精确映射尺寸与九点指纹校验。监控计时与映射生命周期边界见 [Zygisk companion 文档](./zygisk-companion/README.md)。

## 使用方式

1. 从 [Releases](https://github.com/RRRRUDDDD/FreeMOMO/releases) 下载 `freemomo.apk` 和 `freemomo-zygisk.zip`。
2. 安装 `freemomo.apk`。
3. 在 LSPosed 中启用 FreeMOMO，作用域只勾选墨墨背单词（`com.maimemo.android.momo`）。
4. 在 KernelSU/Magisk 模块管理器中安装 `freemomo-zygisk.zip`。
5. 安装或更新 APK 后，完全退出墨墨背单词再打开，使 LSPosed 加载新模块。Hook 安装成功后会显示“FreeMOMO 已找到 Hook 函数”。
6. 需要自动适配的其他版本会寻找 Hook 方法并将结果保存在本地；首次启动请停留一会儿，看到上述 Toast 后完全退出并再次打开墨墨背单词。

## 许可证

本项目基于 [MIT License](./LICENSE) 开源。

## 免责声明

本项目仅供学习和研究使用，请勿用于商业或非法用途。使用本项目产生的一切后果由使用者自行承担，本项目不提供任何明示或暗示的适配性、安全性或合法性担保。
