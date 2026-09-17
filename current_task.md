# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-17 傍晚（/simplify 清理收口时移出活快照）」；再往前是「SDK 1.0.0 公网发布后精简：精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 · 发版 server `docs/ops/RELEASE.md` ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-17 夜：结束帧 / 迟到帧合成一张表（队列 5 的「迟到帧」那条）**：四份「这个状态怎么结束」合进 `statemachine/IMCallExit.kt`
（`reduceAct` 退出方法 / `forceEndFrames` / `handleLateFrame` 与 `handleInviteOk` 补发 / `IMRequestFailures` 失败收场集合），
`CallExitTableTest` 逐条对 `call_fsm.json`，与 iOS `IMCallExit` 同一张表。行为不变。
**没挪进请求关联层**：`call.connected` 是推送不是应答，关联层看不见；房间机的迟到 `room.join.ok` → `room.leave` 只有一处，不动。
定时器样板本端不再抽：/simplify 已收成 `IMScheduler` + Kit 注入式小类 + `postResetIfEnded`。

**2026-09-17 夜：「调用结果回给调用方」（2.0.0，server `docs/design/ACTION_RESULT_DESIGN.md`）已提交 `6fac9d7`（未推送），code-review 已过。** `test.sh` 6 步全绿（新增 `ActionResultTest` 16 条）。
- 发起类方法加可选 `IMResultCallback<T>`（主线程、恰好一次），不传回调失败退回 `onError`；`onError` 加 `forType`；新增公开 `IMRTCError`；`call` 结果值是 callId；`login` 结果是第一次握手的结论。
- 核心循环拆到 `IMFrameLoop`、两个事件出口拆到 `IMEngineEvents`（门面 512 行）；退出类失败本地收场；destroy 后发起类 2005、清理 / 提示类空操作、`forceEnd` 竞态已修、调度器收不下时当场 2005。
- Kit 不再在 `onError` 里靠 `joining` 猜归属：拨号 / 加入 / 加人的文案从结果取码（`IMKitResults`）。**真机未验**：joinCall 1202 / 1402 / 1409 三种文案、拨号拿到 callId、通话中断网再挂断。

**2026-09-17 傍晚：四仓 /simplify 清理做完并推送（本仓 `5ad5bad`…`e11761f`，`test.sh` 6 步全绿；用户已复看，正常）。**
- **行为修复 `5ad5bad`**：关闭码 4400 原先落进默认分支一直重连，改从 `IMCloseCode.shouldReconnect` 取判据，只报 `onDisconnected(4400, false)`、不抛 `onKickedOut`，对齐 iOS / Web（CLIENT_PARITY 那句「4400 四端都不重连」此前对 Android 不成立，现在成立）。JVM 单测 8 条，真机没造 4400。
- Demo 通话记录补齐 answered_elsewhere / rejected_elsewhere / room_closed，kicked 改「已被移出」。
- 行为不变：`applyOutput` 按引用短路 `claimRemoteTracks`；控制按钮 setter 判重、头像底纹按 `avatarKey` 判重；`Wire` 统一取值、`invalidStateOutput` 合并；`View.dp()` 收进 `IMKitTheme`（统一截断）；`scheduleResetIfEnded`；`startCapture(profile)` / `safeRemoveSink` / `hasPermission`；删零调用的 8 个 `IMKitIcon` 与 drawable、`Settled.OFFLINE`、`IMCallOverlay.isAttached`。
- 09-17 夜五件、下午三条待办、16:35 收进小窗的细节已移进 archive。

## 下一步

0. **还没验的**：视频通话拨出中收起、接通后球变视频缩略没点过；1v1 视频默认走听筒（改前就这样，要不要默认扬声器待定）。
1. **真机窗口清单**：
   - 铃声：蓝牙耳机场景 + **补记机型与 Android 版本**（O+ / O- 焦点 API 走的哪条）。
   - 老批次（forceEnd 断网 / 秒挂、后台重连节奏、1v1 视频细节）：archive「2026-09-15：forceEnd …真机验收清单」。
2. 2.0.0 调用结果改造：真机验（见当前焦点）→ 用户通知后发版。
3. 待办：静默失败清单 `../im-rtc-server/docs/ops/silent-failure/android.md`。

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
  **关闭码要不要重连只看 `IMCloseCode.shouldReconnect`**（与 `error_codes.json` 的 `close_codes[].reconnect` 一致），特例只有两条：4401 数到 3 次、1000 非自主关闭照常重连。

**体量（都贴着 600）**：`IMCallKit.kt` 595、`IMCallView.kt` 599、`IMWebRTCAdapter.kt` 595、`IMCallViewState.kt` 588、`IMSignalConnection.kt` 574——下次改先拆。

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
