# SillyTavern Launcher v0.3.0

非官方 Android 10+ SillyTavern 网页启动器，Java + Android System WebView，**不包含 SillyTavern 服务端**。首启绑定 HTTP/HTTPS 酒馆网站；以后打开 App 直接进入该地址。使用 GitHub Actions 构建 Debug APK。

> 本版针对实机反馈的「保存文件失败：Failed to fetch」及挖孔屏顶部遮挡改进。**已运行本地 JavaScript 模拟测试，但没有在本环境实际编译 Android APK 或验证你的酒馆插件；对于某些非标准导出方式无法保证完全兼容。**

## 新功能 / 修复

| 功能 | v0.3 行为 |
|---|---|
| HTTP / HTTPS | 保持明确指定的协议；未写协议时默认 HTTP；网络安全配置允许 HTTP。 |
| 顶部挖孔屏适配 | Android 11+ 全屏时依据 WindowInsets（稳定状态栏高度 + DisplayCutout 安全区）预留顶部距离；Android 10 采用系统避让策略；设置页正常显示系统栏。 |
| 常见 Blob 导出 | 优先捕获 `URL.createObjectURL(blob)` 中真实的 Blob 对象，因而不依赖随后可能被撤销的 `blob:` URL。兼容 `<a download>.click()` 和 DOM 点击，保留 `fetch(blobURL)` 兜底。 |
| `data:` 文件导出 | 本地解码 base64 或 percent-encoded data URI，交给安卓文件写入桥接。 |
| 大文件导出 | 48 KiB 分块经 AndroidX WebMessage 传输；MediaStore 写入由单线程任务执行，不在 UI 线程做磁盘 I/O；单文件上限 1 GiB。 |
| 文件下载 | HTTP(S) URL 交给 Android DownloadManager，附加当前 URL 的 Cookie / User-Agent 和同源 Referer（不会向其他来源发送当前聊天地址）。 |
| 文件导入 | WebChromeClient 文件选择器调用 Android 文件管理器，并将允许的 `content://` URI 返回给 WebView。支持网页请求的多个文件。 |
| 删除 | 保留网页原生的角色、预设、聊天等删除操作；启动器不单独删除服务器数据。 |
| 文件诊断 | 设置 → 文件与导入导出 → **测试导出**，能生成 `ST-Launcher-export-test.txt`，检查 Android 原生导出桥是否可用；另有“打开系统下载列表”。 |
| UI | 统一深夜蓝、深灰渐变卡片、黄色重点按钮；增强绑定页、设置页标题区、HTTP/HTTPS 状态标记和文件管理入口。 |

## 安装、更新与自动编译

1. 下载项目 ZIP，**解压**，将里面所有项目文件（包含隐藏的 `.github` 目录）上传到 GitHub 仓库根目录，覆盖旧版。
2. Commit 至 `main` 或 `master`，打开仓库 **Actions → Build Android APK (v0.3)**，选择这次**新提交**对应的运行（不要在旧失败任务上使用 Re-run jobs）。
3. 依次应经过 `Install Android SDK 35 (no deprecated tools)`、`Test WebView JavaScript and native export protocol`、`Compile installable debug APK`。
4. 成功后在 **Artifacts** 下载 `SillyTavernLauncher-v0.3.0-debug-APK`，解压获得 `app-debug.apk`。

工程使用 JDK 17、Android Gradle Plugin 8.9.2、Gradle 8.11.1、compileSdk / targetSdk 35、AndroidX WebKit 1.14.0，最低 API 29（Android 10）。工作流绕过旧版 SDK `tools` 安装故障。

### APK 更新提醒

GitHub Actions Debug APK 的签名可能因运行器变化而不同。出现“不能覆盖安装”并非代码问题，需要正式配置稳定签名的 Release APK 才可确保持续无损更新。卸载后再装会移除 Android 应用内部网站 Cookie / 本地数据；**先按酒馆本身的流程备份重要资料**，尤其是浏览器本地存储的内容。服务器存储的数据通常不因卸载启动器而删除。

## 导入、下载、导出和删除如何工作

- **导入：** 酒馆网页发起 `<input type=file>`，启动器让系统文件选择器返回一个或多个 `content://` 文件 URI。该流程不把文件传到启动器作者服务器。
- **普通下载：** HTTP(S) 文件链接进入 Android 系统下载队列；使用当前下载 URL 所属站点的 Cookie，保存到公共 `下载/SillyTavern/`，文件名加时间前缀避免冲突。某些要求 POST / CSRF token / ServiceWorker 鉴权的插件下载仍可能需要另外适配。
- **Blob 导出：** 酒馆网页在主框架中创建的 File/Blob，经 `URL.createObjectURL` 被拦截并保存 Blob **对象引用**，因此即便插件随即调用 `URL.revokeObjectURL`，仍能导出。按块发送给安卓的 MediaStore，保存到 `下载/SillyTavern/`，导出成功后显示 Toast。
- **Data URI 导出：** 支持常见 Base64 / URL 编码文件；大体量 data URI 自身会占内存。
- **删除：** 由酒馆网站页面上的确认弹窗和网络请求执行。启动器不会绕过确认，也不会通过“清缓存”删除服务器聊天或角色。

### 用手机定位“Failed to fetch”

1. 进入酒馆，打开通知栏 → 软件设置 → **文件与导入导出 → 测试导出**。
2. 打开 Android 文件管理器 → 下载 → SillyTavern，检查 `ST-Launcher-export-test.txt`，内容有“中文保存测试 OK”。
3. **测试文件能保存，但某个预设导出仍失败：** 很可能是该插件在 iframe、ServiceWorker 或其它隔离上下文中生成文件，或使用非标准保存方式；请记录具体导出按钮/插件名称及其错误提示。
4. **测试文件也不能保存：** 检查 Android System WebView 是否更新；确认酒馆已加载完成、存储空间足够，并记录安卓版本、错误信息。查看已绑定地址是否与网页主框架的实际 Origin 一致。
5. 检查下载时，系统下载列表显示“已加入队列”**不等于下载成功**；应以文件管理器看到文件为准。

**局限：** 安装脚本是在 WebView 的主框架页面加载后注入的。如果 URL 在注入前已经生成、或由跨域 iframe 创建、或扩展直接使用 File System Access API，可能无法捕获实际 Blob。此时 `fetch(blobURL)` 兜底仍可能失败；不能保证兼容所有版本及插件。

## 后台保活的真实机制

此启动器不会在后台独立运行 SillyTavern，也不代替酒馆服务器发送聊天请求。运行剧情时：

- **保活开关关：** Activity 切换到后台，使用正常 Android WebView 生命周期；网页可能继续运行一段时间，也可能被系统限制。
- **保活开关开：** 用户在设置中启动 `ForegroundSessionService`，以 `specialUse` 前台服务类型发出状态通知（`START_STICKY`）。Android 通常会提高包含前台服务的应用进程优先级；新版后台时不主动调用 `WebView.onPause()`。同时设置中有“打开系统电池优化设置”入口供用户自行调节。
- **切到其它 App / 熄屏：** 如果进程和 WebView 仍运行，可能继续收取流式数据、响应生成结束事件并提醒。但 Doze、厂商策略、Chromium 冻结、网络断开都可能造成延迟或失败。
- **进程真的被系统杀掉：** `START_STICKY` 只表示系统有机会重建前台服务；不会自动恢复原来 WebView 的 JS 上下文/流式连接。**因此不能保证剧情后台生成绝不间断。** 服务端是否继续生成，取决于 SillyTavern 和你所用的 API 及代理配置。
- **完成提醒：** 只有 WebView 的 `generation_ended` 等事件仍能被执行并通过同源消息桥通知应用时，才能响铃或震动。前台本地提醒，后台使用通知渠道，需授权通知权限。

前台服务会提高电量消耗；Android 可能限制某些设备/版本的 `specialUse` 类型，不保证任何厂商都能无限期保活。**不持有 CPU 唤醒锁，也没有绕过系统强制停止。**

Android 官方参考：[后台前台服务](https://developer.android.com/develop/background-work/services/fgs/changes)、[WebView 生命周期](https://developer.android.com/reference/android/webkit/WebView)、[屏幕挖孔](https://developer.android.com/develop/ui/views/layout/display-cutout)。

## 其它设置与隐私

- 首次输入 `http://192.168.x.x:8000` 或 HTTP/HTTPS 域名并绑定；以后自动进入。通知栏可打开设置或注销绑定。
- 显示：全屏沉浸模式可随时切换；会保护挖孔屏安全区，不改变 SillyTavern 网站自身排版。
- 通知：回复完成提醒、提示音和震动都可以单独开关。系统通知权限及渠道配置仍受用户系统设置控制。
- 输入：可禁用 Android 原生全选/粘贴菜单；该开关也可能影响酒馆输入框粘贴。
- 存储：可立即清理网页资源缓存，或退出 App 时自动清缓存；**不主动清除 Cookie、localStorage、网站服务端数据**。下载文件也不会因清缓存而删除。
- HTTP 明文访问有网络窃听风险，仅在可信局域网或 VPN 中使用；优先为公网站点配置 HTTPS。
- 消息桥仅允许当前绑定站点的**同源主框架**发起文件导出/回复提醒，不允许任意第三方 frame 调用 Android 文件写入。

## 本地检查和仍需真机验证的内容

本项目包含：

- `tests/BoundUrlSmoke.java`：HTTP/HTTPS 绑定、同源判断。
- `tests/reply-events-smoke.js`：正常结束、主动停止、重复渲染等 5 组事件。
- `tests/blob-export-smoke.js`：完整虚拟 JS → Native 协议，覆盖测试 TXT、立即撤销 Blob URL、网页 DOM 点击、data URI。
- `tests/MOBILE-QA.md`：真实手机上的导入 / 导出 / 下载 / 删除 / 挖孔屏 / 锁屏复测表。

**这些测试不等于 Android 真机通过。** 已有 v0.2 真实运行但导出失败的反馈，所以 v0.3 的实际改善仍需要你验证；收到具体失败日志/插件信息后可以继续针对性修复。

MIT License，非 SillyTavern 官方产品。
