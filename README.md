# SillyTavern Launcher v0.2.1

**Android 10+ · Java · WebView · GitHub Actions 自动构建 Debug APK**

一个第三方、非官方的 SillyTavern Android 网页启动器：第一次绑定酒馆 URL，之后自动进入绑定的酒馆；保留上一版网页导入/导出/下载，并加入深色电竞风设置界面及可调功能。

> 本工程是源码原型，尚未在真实设备上构建或验证所有酒馆扩展。**并不包含、运行或托管 SillyTavern 服务端。**

## v0.2 新增

| 功能 | 设置入口 | 默认状态 | 实际行为 |
|---|---|---|---|
| 全屏沉浸模式 | 显示与沉浸 | 关 | 酒馆页隐藏顶部工具栏及 Android 系统栏；从屏幕上缘滑动可临时显示状态栏 |
| 增强后台保活 | 后台与运行 | 关 | 由用户显式开启前台服务，显示状态通知；同时提供系统电池优化设置入口。**不能保证 WebView 永不冻结/进程永不退出。** |
| 回复完成提醒 | 回复完成提醒 | 关 | 监听 SillyTavern `GENERATION_STARTED`、`MESSAGE_RECEIVED`、`GENERATION_ENDED`（或角色消息渲染事件）；用户主动停止生成不提醒。**生成异常、第三方扩展和不兼容版本可能影响判断。** |
| 提示音 | 回复完成提醒 | 开（仅当总开关打开时生效） | 前台本地播放系统提示音；后台依赖通知渠道和通知权限 |
| 震动 | 回复完成提醒 | 关 | 前台振动一次；后台依赖通知渠道和通知权限 |
| 禁用 Android 原生文字工具栏 | 输入与交互 | 关 | 抑制 WebView 的原生复制、粘贴、全选浮动工具栏；可能同时影响聊天输入框内的这些操作，不影响酒馆自身的自定义菜单 |
| 退出时自动清缓存 | 缓存管理 | 关 | 使用菜单的「退出应用」结束 Activity 时清理 **WebView 网页资源缓存**，不清 Cookie / localStorage / 服务器聊天数据；系统强杀进程时不能保证触发 |
| 立即清理网页缓存 | 缓存管理 | 手动操作 | 立即 `WebView.clearCache(true)`；**不等于清除全部浏览数据** |

### 通知栏快捷操作

已绑定酒馆时，通知提供 **软件设置** 和 **注销绑定** 两个操作。点击「软件设置」进入新设置界面，点击「注销绑定」二次确认后返回 URL 绑定页。

> Android 13+ 要求通知授权，Android 14+ 及部分厂商 ROM 允许划掉或隐藏持续通知，**不能保证通知永远钉住**。仍可从 App 顶部 `⋮ → 软件设置` 进入设置。全屏模式下，滑动顶部唤出通知栏；如果通知被禁用，在酒馆首页（WebView 无可返回历史）按系统返回键也可打开设置。

### 界面设计

采用参考图风格的深夜蓝 `#080D19`、深灰卡片 `#171E2D`、黄金高亮 `#FFD43B`：顶部英文小标签、粗体中文标题、圆角分组卡片、独立黄色开关、低干扰辅助文字。酒馆网页保留原本的 SillyTavern UI，不强行注入网站 CSS。

## 首次连接和数据兼容

- 输入 HTTP / HTTPS 地址，例如 `http://192.168.1.5:8000`，点击「绑定并进入酒馆」。地址保存在 `SharedPreferences`，下次自动打开。
- 安卓手机中的 `127.0.0.1` 指的是**手机本机**。如果 SillyTavern 在电脑运行，要用电脑的局域网 IP、HTTPS 反向代理地址或 VPN 地址。
- 保留上一版网页文件功能：原生文件选择器导入、普通 HTTP(S) 下载、常见 `blob:` / `data:` 导出，文件位于 `Download/SillyTavern`。
- 酒馆内部删除角色、聊天、世界书等操作由**远端网站**正常执行。
- 导出方式因 SillyTavern 版本、扩展而异，不能保证所有非标准插件工作。HTTP 下载可能受特殊鉴权规则影响；Blob 单文件最多 1 GiB。
- 禁用原生文字工具栏只影响**当前 WebView 的 Android 选择工具栏**，不修改系统键盘、系统剪贴板和酒馆服务器配置。

## GitHub Actions 自动构建

已有 v0.1 仓库的升级方式：解压 v0.2 ZIP，把里面全部内容（**包含 `.github` 隐藏目录**）覆盖到 GitHub 仓库根目录并提交。

新建仓库的方式：

1. 打开 https://github.com/new ，创建 `SillyTavern-Launcher` 仓库。
2. 将本项目 ZIP **解压后**的文件上传到仓库根目录。请上传 `.github/workflows/build-apk.yml`，不要仅上传 ZIP 本身。
3. 提交到 `main` 或 `master` 会自动触发工作流，也可在仓库 `Actions → Build Android APK → Run workflow` 手动启动。
4. 成功后，打开该次运行，在页面底部 **Artifacts** 下载 `SillyTavernLauncher-v0.2.1-debug-APK`，解压得到 **`app-debug.apk`**。
5. 将 APK 安装到 Android 10 及以上的手机即可。

构建版本：Android Gradle Plugin **8.9.2**、Gradle **8.11.1**、Java **17**、compileSdk **35**、targetSdk **35**、AndroidX WebKit **1.14.0**。Actions 安装固定 Gradle 版本，不依赖本地 wrapper。

### 更新 APK 签名说明

当前 Github Actions 构建的是 Debug APK，其**临时 Debug 签名可能在不同工作流间变化**，覆盖安装出现“签名不一致”时，可能必须卸载旧 App（绑定地址和 App 中的 WebView 登录态可能丢失；服务端酒馆数据不受影响）。长期更新使用请自行为 Release APK 配置**固定且安全保存的签名密钥**。暂未自动发布 Release APK。

## Android 后台与通知限制

- Android 12+ 对后台启动前台服务有限制。本项目仅从用户打开 App 或在设置中开启时尝试启动；开机广播**不会直接启动前台服务**。
- Android 14+ 采用 `specialUse` 前台服务类型及相关权限和 subtype 声明。该类型可能受到 Android / 设备限制；如向 Google Play 上架，需根据其最新政策提交用途审核，不保证获批。
- Android 会限制后台 WebView 的脚本、定时器、网络与进程执行；前台服务**不等于**让服务器端生成或客户端监听永不中断。因此对于后台回复提醒，应在具体机型、酒馆版本、API 模式和电池管理环境中测试。
- 如果关闭系统通知权限，后台回复提醒与快捷操作可能不可见。设置页提供「系统通知权限与提醒渠道」按钮。
- 后台通知渠道一旦创建，用户可在**系统通知设置**里单独覆盖提示音/震动；Android 不允许 App 强行覆盖用户修改过的渠道设置。

参考文档：
- SillyTavern 官方事件 API：https://docs.sillytavern.app/for-contributors/writing-extensions/
- Android 前台服务类型：https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 通知权限：https://developer.android.com/develop/ui/views/notifications/notification-permission

## 安全和隐私

- 只绑定自己信任的酒馆地址。绑定与设置均在本机，不上传到启动器开发者服务器。
- WebView 的文件导出与完成事件桥接限制为绑定网站的**同源主框架**，不能用于跨域任意调用。
- 不绕过 TLS 证书错误；默认禁止从 `file://` 打开任意本机文件及不安全文件跨域读取。
- 局域网 HTTP 为明文传输，尽量只在可信网络 / VPN 下使用。
- 「注销绑定」不清除服务器数据；「清理网页缓存」也不清理 Cookie/登录态。
- 需要确认用户自定义 SillyTavern 插件是否存在额外的权限、登录或文件访问需求。

## 代码结构

```text
.github/workflows/build-apk.yml             GitHub 自动编译任务
app/src/main/java/io/github/stlauncher/
  MainActivity.java                          界面、设置页和酒馆 WebView
  LauncherOptions.java                       各个功能选项本机持久化
  BlockableWebView.java                      原生文本菜单拦截
  ForegroundSessionService.java             用户主动开启的前台服务
  NotificationHelper.java                    常驻通知及回复完成通知
  ReplyNotifier.java                         前台铃声、震动
  BlobExportManager.java                     Blob 分块写入下载目录
  DownloadHandler.java                       普通 HTTP(S) 下载
  BoundUrl.java                              网址规范化 / 同源校验
  BootReceiver.java                          开机恢复普通绑定通知
app/src/main/assets/
  blob_export.js                             网页导出适配脚本
  reply_events.js                            SillyTavern 生成完成事件监听
```

## 测试状态

已在当前环境进行 JavaScript 语法检查、Android XML 解析检查及事件监听模拟测试。**当前环境未预装 Android SDK/Gradle 且无法访问外网，尚未真正执行 `assembleDebug`，也没有实机验证**。首次 GitHub Actions 编译和安装后，请重点检查：①地址绑定；②通知栏进入设置/注销；③全屏退出路径；④保活开关及系统权限；⑤普通/流式回复提醒；⑥角色卡导入、聊天导出；⑦缓存清除前后保持登录。

MIT License。非 SillyTavern 官方产品。


## 2026-11 GitHub Actions SDK 修复

如果旧版工作流在 `Setup Android SDK` 报错 `Failed to find package 'tools'`，
原因是 `android-actions/setup-android@v3` 仍尝试安装已移除的旧 SDK `tools` 包。
本工程已将其更新为 `android-actions/setup-android@v4`，并明确设置
`packages: 'platform-tools'`。无需修改 App 功能代码，也无需安装本地 Android SDK。

将包括隐藏 `.github` 文件夹在内的项目文件上传到 GitHub 仓库根目录，
然后在 Actions 里重新运行 `Build Android APK`。
如果 SDK 安装通过但后续 Java 编译出现新报错，请提供该步骤日志。


## 2026-11-12 SDK 旧 tools 包错误修复

本版本 `.github/workflows/build-apk.yml` 不再使用 `android-actions/setup-android`，直接调用 GitHub `ubuntu-24.04` 运行器内置 SDK 的 `sdkmanager`，明确安装 `platform-tools`、`platforms;android-35`、`build-tools;35.0.0`，不会执行 `sdkmanager tools`。

上传文件以后请在 GitHub 仓库提交新的 commit（确认根目录 `.github/workflows/build-apk.yml` 已被覆盖），到 **Actions > Build Android APK (SDK bypass fix)** 查看新运行记录。不要对以前失败记录点 **Re-run jobs**，它仍使用原始提交。首次运行可在 Actions 中选择 **Run workflow**。

本地只验证了工作流 YAML 格式及项目资源文件，GitHub 上最终编译需以 Actions 运行结果为准。


## v0.2.1：HTTP 酒馆连接修复

上一版对未写 `http://` 的公共域名会自动补为 `https://`。本版改成：

- 输入 `http://example.com:8000`：原样使用 HTTP，**绝不自动转换成 HTTPS**。
- 输入 `example.com:8000`（省略协议）：默认 `http://example.com:8000`。
- 输入 `https://example.com`：原样使用 HTTPS，绝不自动降级。
- Manifest 保留 `usesCleartextTraffic=true`，并新增 `res/xml/network_security_config.xml`，显式允许用户配置的 HTTP 站点使用明文通信。因地址可为任何域名/IP，无法事先限定域名，请仅绑定可信来源。
- **已绑定旧的 HTTPS 地址？** 进入通知栏 → 软件设置，当前网站区域点击「当前网站只支持 HTTP？点击切换」并确认，就会把已有 HTTPS 地址改成 HTTP 并保存。也可以注销绑定，重新输入完整的 `http://` 地址。
- 如果明确输入 HTTP 后网页仍跳转至 HTTPS，则可能是服务器/反向代理发出的重定向，或者浏览器缓存的 HSTS 规则。这种情况需检查酒馆站点配置；启动器不会绕过服务器重定向或证书校验。
- `http://` 不是加密协议，不建议在公网直接发送角色聊天内容、Cookie 或登录凭据。优先使用 HTTPS 或受信任的 VPN。
- 修复包含在 `.github/workflows/build-apk.yml` 中，继续使用绕过旧版 SDK `tools` 的工作流。
