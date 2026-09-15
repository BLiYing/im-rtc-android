# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-11 精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-15：宿主对接 M1 → M2 → M8（群通话 chat_group_id / IMInviteMemberProvider / call.join）落地。未提交；`./scripts/test.sh` 全绿（6 步，单测 engine 164 / webrtc 36 / uikit 64 / demo 26）；真机未验（无设备可用，本轮只验到编译 + JVM 单测）。**
契约见 `../im-rtc-server/docs/design/HOST_INTEGRATION_DESIGN.md` §3.2/§3.3/§3.4，一致性向量已由另一人改好（`call_fsm.json` 两条新用例 + `member_joins_ongoing_group_call` 补字段、`envelope.json` 两条新默认值用例、`error_codes.json` 加 1409）。

- **M1（call-engine）**：`CallFrames.INVITE/INCOMING` 加 `chat_group_id`，`CONNECTED` 加 `caller`/`chat_group_id`/`user_data`；`IMCallContext` 记 `caller`/`chatGroupId`/`userData`，`handleConnected` 取 `call.connected` 的值、为空回落到 `call()` 选项 / `call.incoming` 记下的值；新增 `IMCallOptions`（`@JvmOverloads`：isGroup/chatGroupId/userData/timeoutSec）与 `call(userIds, mediaType, options)`（校验拆在 `IMCallInvite.kt`，超限走 `IMCallOptionsGuard`，本地先拦、不上线路、与「callee 里有自己」同一出口）；`IMErrorCode.INVITE_DENIED=1409`；`onCallReceived`/`onCallBegin` 改签名加 isGroup/caller/chatGroupId/userData（**不留旧签名**）。
- **M2（call-uikit）**：新增 `IMInviteContext`、`IMInviteCandidate` 扩字段（avatarUrl/subtitle/selectable/unselectableReason）、`IMInviteMemberProvider`（loadCandidates/presentInvitePicker/canInvite）、`IMPickedCallback`；`IMCallKitConfig` 加 `inviteMemberProvider`/`allowsManualUidInput`；`IMInvitePicker` 按 `IMInviteFlow` 的优先级（宿主接管 > provider > 静态 inviteCandidates > 空态）重写：300ms 防抖、generation 计数作废旧请求、滚到底翻页、加载中/失败(重试)/10s 超时三态、participantUids 已在通话中不可选、selectable=false 置灰。`IMCallViewState` 加 `chatGroupId`/`userData`；`incoming`/`outgoing`/`begin` 三个 reducer 带上这两个字段（`begin` 用可空参数「不传不改」，保留旧 5 参数测试调用点）。
- **M8（本仓部分）**：`IMCallEngine.joinCall` 已有（M1 前就在）；新增 `IMCallKit.joinCall(callId)`（直接进 CONNECTING「接通中…」，`IMCallViewReducer.joining`）；`IMJoinCallState.joining` 记状态，`IMKitListener.onError` 按它把 1409 分成「对方暂时无法被邀请」（加人）/「无法加入该通话」（加入）两句文案，其余 join 失败码统一给后一句。
- **Demo**：`DemoInviteProvider`（真实联系人排前 + 40 个假成员分页，`fail`/`slow` 模拟失败/超时）挂到 `kitConfig.inviteMemberProvider`；`DialerScreen` 加「加入进行中的群通话」卡片（call_id 输入框 + 按钮）；群呼带 `chatGroupId="demo-group"`；`JavaApiCheck.java` 补 `IMCallOptions`、新 `onCallBegin`/`onCallReceived` 签名、Java 版 `IMInviteMemberProvider`、`IMCallKit.joinCall`。

**体量**：`IMCallEngine.kt` 595（拆出 `IMCallInvite.kt` 才压回 600 以内，之前一度 612 超标）、`IMCallKit.kt` 584（`showInvitePicker` 逻辑搬进新的 `IMInviteFlow.kt`）、`IMCallViewState.kt` 541、`IMSignalConnection.kt` 598、`IMCallView.kt` 591——**都在红线内，但没余量了**，下次改这几个文件之前先想好拆哪块。

## 下一步

**真机验收（本轮完全没做，环境不允许连真机/起服务端）**：
1. **M1**：group 通话下发 `chat_group_id`/`user_data`/`timeout_sec`，被叫 `onCallReceived` 与接通后 `onCallBegin` 真的带到；`call.join` 场景（frank 在另一台设备加入）`onCallBegin` 能拿到 caller/chatGroupId。
2. **M2**：`IMInvitePicker` 真机走一遍——300ms 防抖是不是真的等到停手才发请求、滚到底是否稳定触发下一页、`slow` 搜索词 10 秒后是不是真的转成失败态、`fail` 搜索词的重试按钮能不能把请求发出去。
3. **M8**：Demo 两台设备各登一个账号，A 发群通话，B 用 A 日志里的 call_id 在「加入进行中的群通话」里加入，确认不振铃直接接通；服务端配了邀请鉴权回调时验 1409 两句文案分得开（本仓这轮没法配服务端，等服务端那位实现完 `call.invite_check` 回调后再联调）。
4. 老批次真机项（forceEnd 断网/秒挂、后台重连节奏、1v1 视频细节）见 `current_task.archive.md` 与 server 仓「跨端待验」，本轮没有动它们。

**待办**：
- **`IMCallKit.notifyOutgoing` 没有带 `IMCallOptions` 的重载**（`IMCallKit.kt` 体量已经到顶，这次先省了）：宿主自己调 `engine.call(options)` 又想用 Kit 画拨出界面时，`notifyOutgoing` 目前带不出 chatGroupId，界面上加人入口会看不到候选——这类宿主目前的替代路径是 `placeCall(peers, mediaType, options)` 直接经 Kit 拨出。
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
