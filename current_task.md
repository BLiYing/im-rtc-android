# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-11 精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-15：群通话里的任何人都能加人（服务端同日放开）。未提交；单测已跑，真机未验。**
`IMCallViewState.canShowInvite` 去掉 `role == "caller"`；新字段 `caller`（被叫侧记发起人）→ `IMCallKit.showInvitePicker` 不列发起人（离场后服务端拉不回来）；1407 提示改「你已不在通话中，无法添加成员」。
真机验：被叫接通后右上角有加人按钮，加人后对方响铃接通。

**2026-09-15：对齐 iOS 的 `forceEnd`（红键等不到结束事件时引擎也收场）+ 拨出中没 call_id 的补救。已提交 `b9e2977`，两笔小账随后单独一笔；`./scripts/test.sh` 全绿（6 步，单测 engine 155 / webrtc 36 / uikit 63 / demo 26）；PKD130 真机 10:08 验过看门狗 → `forceEnd`（见下一步 0）。**
起因 09-13 14:53~14:58 iOS frank：挂断帧没到服务端，看门狗只收了界面，Engine 留在通话与房间里，其余端一直看得见他。本端看门狗原先同一个缺口。
上一件（对端重开摄像头刷新）已提交 `cc111f0`、20:04 真机验过；「后台重连节奏」`2c9c2fe` 还没真机验。

- `IMCallEngine.forceEnd()`（Java 可调、任何线程）→ `IMForceEnd`：读 `@Volatile ctx` 挑帧（纯函数 `IMEngineMachine.forceEnd`，`EngineStateMachineForceEnd.kt`），
  `IMSignalConnection.fire` 在调用方线程直发（req_id `f-N` 不登记，应答当迟到丢掉）；本地收场排回 engine 线程，先比对 call_id / room_id，拨出中 invite.ok 刚到则补发 cancel。
- 状态机 idle 分支：房间机 `room.join.ok` → 补 `room.leave`、其余房间帧丢；通话机 `call.invite.ok` → 补 `call.cancel`、`call.connected` → 补 `call.hangup`，其余照旧丢。
- 门面：房间 idle 时迟到的候选 / SDP 不交给媒体层；请求往返 ≥ 2s 记 `请求往返慢`。
- UIKit：红键整块拆到 `IMRedButton.kt`（按下记 `按下红键 action= phase=`，看门狗到点 `endLocally` 后 `engine.forceEnd()`）；`IMCallViewReducer.ended` 在 IDLE 下原样返回。
- 拆分：`IMMediaDriver.kt`（driveMedia / 补静音）、`IMForceEnd.kt`、`signaling/IMHandshakeGiveUp.kt`。
- 日志：`强制收场 call_id= … frames=` · `强制收场：没有进行中的通话或房间` · `房间已不在，丢弃迟到的媒体帧` · `请求往返慢` · `按下红键`。
- **两个小账已修**（单测覆盖，真机未验）：① 强制收场时长从本端 `onCallBegin` 那一刻算（`IMEngineContext.callStartedAtMs`，`reduce` 统一维护），不再用整通的 `connected_at_ms`；
  ② 拨出中没 call_id 时按取消不发帧、记 `IMCallContext.cancelPending`，`call.invite.ok` 一回来立刻补发 `call.cancel`（不再换回 1401）。

**体量欠账**：`IMSignalConnection.kt` 598、`IMCallView.kt` 591、`IMCallEngine.kt` 583、`IMCallKit.kt` 549。
`IMSignalConnection` 还能挪：socket 代际（`generation` / `closedGeneration` / `TransportListener`）连同心跳。

## 下一步

**真机验收（报通话时间）**：
0. **`forceEnd`**：~~挂断被拒时看门狗兜底~~（09-15 10:08 PKD130 已验：服务端故障注入拒掉 alice 的 hangup 10:08:15.278 → 10:08:18.236 `f-1` 补发被受理、通话结束、alice 回首页）。
   还没验：断网（飞行模式）后按红键——3 秒后 logcat 有 `强制收场` + `没有信令连接`；拨号后立刻按红键（invite 还没回）——被叫不再一直响（Web 端 10:09 已验同一路径）。
   联测做法（adb 坐标、故障注入 curl）见 server 仓 `scripts/dev.sh` 的 `FAULT_INJECTION=1` 与 `/v1/dev/faults`。
1. **后台重连节奏**（`2c9c2fe`）：登录后切后台约 1 分钟（ColorOS 最好），`adb logcat | grep -i signal` 断开→重连间隔走 1,2,3,3,3… 秒（约每分钟 10 次，不是原来的 20 次）。
2. 期间切回前台：立刻重连一次（不等定时器），退避归零。通话中切后台（前台服务在跑）也该走后台节奏。ColorOS 秒杀间隔是否稳定、服务端 5 秒窗口是否接得住，都还没实机数据。
3. 1v1 视频上一轮：控制条收起后点底部叫回控制条（不静音 / 挂断）；挂断后结束画面标题栏不淡掉；开局清晰度——服务端进房 1 秒内有 `上行层已接入 … rid=h`、整通无 `layer=h live=False`。
   自动隐藏那刀已合入 main（`f7cfb05`），未验收。
4. 跨端老批次（含本端「接通前按静音」）清单见 `../im-rtc-server/current_task.md`「跨端待验」。

**待办**：
- 静默失败点清单：`../im-rtc-server/docs/ops/silent-failure/android.md`（逐条状态只在那里）。`ensureCapture` 缓存死 source 那条排在 server「下一步」第 2 条。
- `call-engine/build.gradle.kts` 找向量仍「逐级往上找」，会捡到上层旧克隆（web 已修同类问题）。
- `CLIENT_PARITY.md` 真机验完再改，验之前停 🟡。

## 已知坑 / 限制

**测试 / 工具**
- `SignalConnectionTest` 里 `scheduler.advance(N)` 超过 `2×pingSec`（30 s）会触发心跳超时重连、污染退避断言：压在 30 s 内，或显式 `transport.deliver(PONG, "")`。
- 仓根 `temp_verify.py` 是多会话共用的活文件，本仓自己的验证脚本单独建文件（如 `temp_verify_reconnect_pacing.py`），免得竞争写丢内容。
- 禁止 `org.json`（JVM 单测里是空壳桩）；`protocol/` 与 `statemachine/` 不许 import android.*（门禁守着）。

**真机 / 环境**
- **真机连不上服务端先看链路**：局域网 IP（PKD130 可用），或 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`（**Pixel 2 XL 只能走这条**，和 Mac 同 SSID 不同 AP、ARP 不到）。
  拔线 / `adb kill-server` / 重启后隧道就没了，症状像登录 bug（`Failed to connect to /127.0.0.1:8787`）。判据：手机 ping 得通 Mac 且 Mac 上 `arp -n <手机 IP>` 有表项 → 局域网可用。
- PKD130（ColorOS / Android 15）`pm revoke` 被挡（`SecurityException`）：测无摄像头权限只能去系统设置手动关，或开「USB 调试（安全设置）」。
- 日志回传只有 `-demo-login` 接收口，生产环境是空转的（Pixel 2 XL 上全链路验收过）。
- 七项界面按「默认已验」收口、没实机走过（09-08 拍板，发现问题再回头查）；其中「返回键收小窗」已被证伪（收小窗后远端视频回不来）。

**媒体 / 渲染**
- **iOS 发的永远是单层 H264**（stasel 152 没有 simulcast 工厂），SFU 降层只砸 Android——**排查「为什么只有 Android 糊」先想这条**，别往本仓编码参数上找。换包暂缓，见 `../im-rtc-ios/current_task.md`。
- `maxBitrateBps` 是 h 一层的数、不是上行总量（720p simulcast 要 l+m+h = 2.15Mbps），混用会让 `SimulcastRateAllocator` 给顶层 0 bps、开局只有 l 出包；已由 `simulcastUplinkBudgetBps` + `PeerConnection.setBitrate` 播种兜住。
- libwebrtc 锁 M150（`150.7871.01`），与 iOS M152 不齐可接受；H.264 要专门跨端实测。
- **2006 阈值「3」未校准、Kit 不接 2006**（`when` 没有 `else`）：见 server「已知坑」。
- **摄像头意图必须在进房前给 Engine**（`IMCallKit.syncCameraIntent`）；接通后才开的靠 `publishCameraIfMissing` 补发（多一条无害 `room.mute muted=false`）。
- **关摄像头停的是采集**：进房前关 = `stopLocalPreview`（已发布的不碰），通话中关 = `setMuted` → `setCapturePaused`。进房前切后台采集不停；通话中重开本端预览可能闪一下。
- 本端 track id 必须就是 cid（msid 第二段）；远端轨道按 track_id 认领、不能按 stream id；轨道 / 归属 / 渲染器到达顺序不定，统一在 `bindRemoteTracks` 判重换绑。
- 「人先进来、轨道后到」是常态：摆格子时做的动作（层上报、尺寸、订阅）要能在轨道到达时再做一遍（`invalidateReportedLayer`）。
- `SurfaceViewRenderer` 的 `init` / `setEnableHardwareScaler` / `setScalingType` 只能主线程调，而调度器会吞异常（症状只有全黑）→ 渲染相关一律走 `IMWebRTCAdapter.onMain`。
- `SurfaceView` 一息屏就没、`EglRenderer` 不重画上一帧：回前台某格纯黑 = 那个对端不发帧了，别查渲染器（iOS `CAMetalLayer` 留旧帧，同故障两端长得不一样）。
- 渲染器按 uid 整通复用（`IMCallKit.remoteViews`，每次新建会闪）；小窗叠在全屏画面上要 `setZOrderMediaOverlay(true)`。
- 离房要停媒体，判据是「媒体还有没有人要」（房间与通话都回 idle 才停）。

**UIKit / 平台**
- `IMGrid.dimensions` 默认 aspect 是 0.7 不是 0.5（按 0.5 算 9 人排成 2×5，与 iOS 3×3 对不上）。
- `GridLayout.spec` 不能带权重（正方形失效）；`GridLayout` 会把 `spec(UNDEFINED)` 改写成具体下标，往小改行列数前先把子视图 spec 退回 `UNDEFINED`（`IMCallGridView.apply`）或 `removeAllViews()`——不转屏也踩得到。
- 横排里的占位格高度必须写死 0（裸 `View` 的 `wrap_content` 在 `AT_MOST` 下吃满整高，把控制条顶到屏幕顶上）。
- 小窗吸角要等容器量出来（宽 0 时 `IMPipLayout.origin` 退化成 x=0）。
- 运行时权限只能从 Activity 请求 → `IMPermissionActivity`（透明、不入最近任务）；「问过没」记在 `im-rtc-kit` SharedPreferences。
- 前后台判定只认亲眼看见 started 过的界面（`IMForegroundState`）：SDK 是半路装上的，任何「按数量判前后台」都会错。
- `IMActivityTracker.foreground()` 拿不到通话页，通话中要 Context 一律用 `appContext`。
- 前台服务：通话中必须起（`IMCallForegroundService`）；类型不能降级；类型不能超出已授权权限（Android 14+ 否则在 `onStartCommand` 里循环崩），`start()` 先判 `IMForegroundTypes.granted`。
- 悬浮球默认走应用内浮层、不申请 `SYSTEM_ALERT_WINDOW`（CONVENTIONS §8）。
- `onDisconnected(code, reason)` 只有 4403 当「断开」，其余关闭码当「正在重连」。
- SDK 版本号只改 `call-engine/.../IMCallEngineVersion.kt`（五端统一 1.0.0，握手 `android/1.0.0`）；`demo/build.gradle.kts` 的 `versionName` 是写死的，发版要手动同号。

## 关联工程 / 常用命令

本机（2026-09-05 实测）：JDK 17（`/usr/libexec/java_home -v 17`）· SDK android-36 / build-tools 36.0.0 · `adb` 在 `~/Library/Android/sdk/platform-tools/`（不在 PATH）· Intel Mac（模拟器 x86_64、真机 arm64，两个 ABI 都要能出包）。
真机：**OPPO PKD130 / Android 15**（局域网直连）· **Google Pixel 2 XL / Android 11**（`903KPED2067148`，只能 `adb reverse`）。

- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口：门禁 ×3 + 向量可达 + assembleDebug + 纯 JVM 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ```
