# FreeMOMO![Downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.rud.freemomo/total) ![Downloads](https://img.shields.io/github/downloads/RRRRUDDDD/FreeMOMO/total)

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
5. 安装或更新 APK 后，完全退出墨墨背单词再打开，使 LSPosed 加载新模块。Hook 安装成功后会显示“FreeMOMO 业务 Hook 已找到”。
6. 需要自动适配的其他版本会寻找 Hook 方法并将结果保存在本地；首次启动请停留一会儿，看到上述 Toast 后完全退出并再次打开墨墨背单词。

## 应用内防更新（3.3）

**防更新仅适配墨墨 5.6.00 / 900，且只拦截墨墨主进程内已验证的更新入口。不能阻止三星应用商店、其他商店或系统安装服务替换墨墨 APK。** 如需保持旧版，还需单独管理外部商店的自动更新设置；FreeMOMO 不会自动修改这些设置。

- 先独立安装已验证的升级分发、下载预检查、下载启动、重试和模型安装入口保护，不再因某个通知字段或手动回调不可用而全部撤掉。
- 完整更新适配器校验并安装成功后，保留手动检查和明确主动升级，继续使用原有异步操作授权隔离。
- 完整适配器不可用时，已安装的入口保持阻断，**手动升级也可能暂不可用**。此降级状态不保证过滤所有更新通知，也不能覆盖未知的 JNI 内部路径。
- 安装失败后只在首次 Activity 恢复及其一秒后各重试一次；成功后不重复安装。尚未恢复 Activity 时不会提前执行这两次重试。
- 防更新状态单独提示：已安装、降级、不完整、未安装或版本不支持。“业务 Hook 已找到”只表示业务功能，不代表防更新已安装。
- LSPosed 日志使用 `FreeMOMO: update:<versionCode>`，失败时包含具体成员/注册阶段与异常类型，不输出异常消息或业务参数。

当前 904 不启用上述版本专用保护，会明确提示未适配。3.2.2 的本地测试和构建不等于整包真机更新链验收，详情见 [修复说明](UPDATE-PROTECTION.md)。

## 5.6.05 适配诊断（开发中）

当前尚未声明支持 5.6.05（versionCode 904）。Debug 构建在该版本主进程中只执行一次额外诊断，复用结构扫描并输出 `FreeMOMO: diagnostic:904` 日志；诊断不受旧扫描缓存阻断，也不会把候选结果交给安装器或写入缓存。

日志仅包含方法签名、扫描状态、失败类名和异常类型，不记录业务参数或异常消息。每项最多输出 24 个方法、总计最多 16 个失败类，并报告省略数量；单条诊断内容上限为 1600 字符。Release 构建不运行该诊断。

诊断包不等于修复包；显示、权限、云词库与更新流程需取得运行时证据后分别适配和验收。

## 许可证

本项目基于 [MIT License](./LICENSE) 开源。

## 免责声明

本项目仅供学习和研究使用，请勿用于商业或非法用途。使用本项目产生的一切后果由使用者自行承担，本项目不提供任何明示或暗示的适配性、安全性或合法性担保。
