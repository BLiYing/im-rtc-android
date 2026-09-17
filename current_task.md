# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-17（SDK 1.0.0 公网发布后精简）：精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 · 发版 server `docs/ops/RELEASE.md` ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-17 下午：清掉三条待办（已提交，`./scripts/test.sh` 6 步全绿；本端预览 cid 用户真机验过）。**
- `IMCallKit.notifyOutgoing(peers, mediaType, IMCallOptions)` 重载：宿主自己 `engine.call(options)` 时群号 / `userData` 也进界面。为腾体量把切后台停摄像头拆到 `IMBackgroundCamera`。
- 找向量不再逐级往上：`call-engine/build.gradle.kts` 的 `conformanceDir` 只认主检出 / `.claude/worktrees/<分支>` 两种布局（同 web `e58ec8e`），`RTC_CONFORMANCE_DIR` 相对路径按仓根解析；测试侧 `ConformanceVectors.locate()` 只认系统属性。
- **本端预览对齐 cid**：`IMCallEngine.startLocalPreview(): String` + `attachLocalView(cid, view)`（旧的 `startLocalPreview(view)` 留 `@Deprecated`）。cid 由 `IMLocalVideoCid` 在调用方线程当场发，发布视频沿用；媒体层只剩**一条**摄像头轨道（id = cid），删掉 `PREVIEW_TRACK_ID` 与 `IMPreviewIntent`（起停都回到 Engine 线程上，不再需要那个号）。`IMMediaAdapter` 接口改了：`startLocalPreview(cid)`、新增 `attachLocalView(cid, view)`。**用户真机验过**：拨出中见自己且接通不断、来电页 / 通话中开关摄像头（灯灭 / 亮）、翻转镜像、切后台回来、群通话关着进房后再开（机型未记，下次补）。CLIENT_PARITY v1.40。

**2026-09-17：夜里逐项补了五件（本地已提交、未推送），早上 PKD130 / Android 15 真机补验，顺手修了一个真机才暴露的问题。** SDK 1.0.0 已公网发布（JitPack，MIT），这些进下一个版本。
- `c2c20db` 摄像头打不开 / 中途被抢走回报 2002，下次打开重起采集（`IMCameraEvents`，静默失败审计 android #1）。
  `24787c8` 真机发现 2002 走 `cameraBlocked` 会把按钮锁成「无权限」、整通开不回来 → 改为只关摄像头、按钮可点；**真机验过**：被抢后点开摄像头重出画面，对端恢复。
- `4a5c983` 收 `call.ringing` 抛 `onUserRinging`，群通话里别人加的人也摆占位格（协议批次，server `dd60ca0`）。**真机验过**：bob 加 carol → 手机上「呼叫中…」→ 拒接「已拒绝」约 2s 收掉。
- `a4c9fb0` 通话音频跟随系统：扬声器关着时耳机 / 蓝牙优先、监听插拔（`IMAudioRoutePolicy`）。真机只验了扬声器开关（type 2 ↔ 1），**没耳机 / 蓝牙，插拔未验**。
- `38941d3` 会议房超过一屏「还有 N 人未显示」+ 屏外报 none（M1）；`61d09c6` 打开网络质量图标，「对方网络不佳」只在 1v1。
- **体量**：`IMCallEngine.kt` 600、`IMCallView.kt` 599、`IMSignalConnection.kt` 600 已到硬顶，下次改先拆；`IMCallKit.kt` 拆后约 580。

## 下一步

0. **还没验的**：插拔有线耳机 / 连蓝牙时声音跟着走（手边要有耳机）；会议房「还有 N 人未显示」在 Android 上没凑人数看过；1v1 视频默认走听筒（改前就这样，要不要默认扬声器待定）。
1. **用户自测**（服务端先重启）：发起人挂断后被邀请回来能响铃接听；来电横幅不出现自己的格子；横幅 / 来电页显示的是**把你加进来的那个人**。
2. **真机窗口清单**：
   - M1：群通话 `chat_group_id` / `user_data` / `timeout_sec` 在 `onCallReceived` / `onCallBegin` 真带到；`call.join` 加入方也拿得到。
   - M2：`IMInvitePicker` 300ms 防抖、滚到底翻页、`slow` 10 秒转失败、`fail` 重试。
   - M8：两台设备按 call_id 加入不振铃直接接通；配了邀请鉴权回调时 1409 两句文案分得开。
   - 铃声：蓝牙耳机场景 + **补记机型与 Android 版本**（O+ / O- 焦点 API 走的哪条）。
   - 老批次（forceEnd 断网 / 秒挂、后台重连节奏、1v1 视频细节）：archive「2026-09-15：forceEnd …真机验收清单」。
6. 待办：静默失败清单 `../im-rtc-server/docs/ops/silent-failure/android.md`。

## 已知坑 / 限制

**发布**
- 下次发版：改 `IMRTC_VERSION` 与 `IMCallEngineVersion.VERSION`（不等时 `SdkVersionTest` 红）→ 推 tag（与版本同号、不带 v）→ 请求一次 pom 触发构建。**JitPack 会缓存失败**：失败要在 JitPack 页面删记录再试。
- group 必须是 `com.github.BLiYing.im-rtc-android`（`gradle.properties` 的 `IMRTC_GROUP`），换回别的 uikit 对 engine 的传递依赖会拉不到。

**测试 / 工具**
- `SignalConnectionTest` 里 `scheduler.advance(N)` 超过 30 s 会触发心跳超时重连、污染退避断言：压在 30 s 内，或显式 `transport.deliver(PONG, "")`。
- 仓根 `temp_verify.py` 是多会话共用的活文件，本仓验证脚本单独建文件。
- 禁止 `org.json`（JVM 单测里是空壳桩）；`protocol/` 与 `statemachine/` 不许 import android.*（门禁守着）。

**真机 / 环境**
- **真机连不上服务端先看链路**：局域网 IP（PKD130 可用），或 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`（**Pixel 2 XL 只能走这条**）。拔线 / 重启后隧道就没了，症状像登录 bug。
- PKD130（ColorOS / Android 15）`pm revoke` 被挡：测无摄像头权限去系统设置手动关。
- 日志回传只有 `-demo-login` 接收口，生产环境空转。
- 七项界面按「默认已验」收口（09-08 拍板）；其中「返回键收小窗」已被证伪（收小窗后远端视频回不来）。

**媒体 / 渲染**
- **iOS 发的永远是单层 H264**，SFU 降层只砸 Android——排查「为什么只有 Android 糊」先想这条。
- `maxBitrateBps` 是 h 一层的数、不是上行总量；已由 `simulcastUplinkBudgetBps` + `PeerConnection.setBitrate` 播种兜住。
- libwebrtc 锁 M150（`150.7871.01`），与 iOS M152 不齐可接受；H.264 要专门跨端实测。
- **2006 阈值「3」未校准、Kit 不接 2006**：见 server「已知坑」。
- **摄像头意图必须在进房前给 Engine**（`IMCallKit.syncCameraIntent`）；接通后才开的靠 `publishCameraIfMissing` 补发。
- **关摄像头停的是采集**：进房前关 = `stopLocalPreview`，通话中关 = `setMuted` → `setCapturePaused`。
- 本端 track id 必须就是 cid，**预览与发布是同一条轨道、同一个 cid**（`IMLocalVideoCid`）；本端渲染器与轨道谁先到都可能，统一在 `IMWebRTCAdapter.bindLocalView` 对齐；远端轨道按 track_id 认领；到达顺序不定，统一在 `bindRemoteTracks` 判重换绑。
- 「人先进来、轨道后到」是常态：摆格子的动作要能在轨道到达时再做一遍（`invalidateReportedLayer`）。
- `SurfaceViewRenderer` 的 init / scaler 只能主线程调，而调度器会吞异常（症状只有全黑）→ 一律走 `IMWebRTCAdapter.onMain`。
- `SurfaceView` 息屏就没、不重画上一帧：回前台某格纯黑 = 对端不发帧了，别查渲染器。
- 渲染器按 uid 整通复用（`IMCallKit.remoteViews`）；小窗叠全屏要 `setZOrderMediaOverlay(true)`；离房停媒体的判据是房间与通话都回 idle。

**UIKit / 平台**
- `IMGrid.dimensions` 默认 aspect 0.7；`GridLayout.spec` 不能带权重，往小改行列数前先退回 `UNDEFINED` 或 `removeAllViews()`。
- 横排占位格高度写死 0；小窗吸角要等容器量出来。
- 运行时权限只能从 Activity 请求 → `IMPermissionActivity`；前后台只认亲眼看见 started 过的界面（`IMForegroundState`）；通话中要 Context 用 `appContext`。
- 前台服务：通话中必须起；类型不能降级、不能超出已授权权限（Android 14+ 会循环崩），`start()` 先判 `IMForegroundTypes.granted`。
- 悬浮球默认应用内浮层、不申请 `SYSTEM_ALERT_WINDOW`。
- `onDisconnected(code, willReconnect)`：`willReconnect` 由 `IMSignalConnection` 当场裁决，Kit 直接用 `!willReconnect` 判「已放弃」。

## 关联工程 / 常用命令

本机：JDK 17（`/usr/libexec/java_home -v 17`）· SDK android-36 / build-tools 36.0.0 · `adb` 在 `~/Library/Android/sdk/platform-tools/`（不在 PATH）· Intel Mac。
真机：**OPPO PKD130 / Android 15**（局域网直连）· **Google Pixel 2 XL / Android 11**（`903KPED2067148`，只能 `adb reverse`）。

- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口（6 步）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  RTC_CONFORMANCE_DIR=../im-rtc-server/docs/conformance ./gradlew :call-engine:testDebugUnitTest
  ./gradlew publishToMavenLocal && ./gradlew -PimrtcSdk=local :demo:installDebug   # Demo 用本地包
  ./gradlew -PimrtcSdk=public :demo:installDebug                                    # Demo 用 JitPack 包
  ```
