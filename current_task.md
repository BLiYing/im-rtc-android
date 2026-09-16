# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-15（第二轮）：四端 API 命名对齐 + 宿主对接 M1/M2/M8」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-16（续）：三件事。①② 已提交 `9e2e476`，③ 已提交 `f091ab9` 且真机验收通过。**

**① 协议新字段 `call.incoming.inviter`（「谁邀请的你」，四端同步改，本仓这一份）。**
- 线路字段 `inviter`：首次邀请 = 主叫；`call.invite_more` 加进来的人 = 发那条加人请求的人。
  **空串回落到 `caller`**，回落做在 `CallStateMachineRecv.handleIncoming`，宿主永远拿得到一个非空的人。
- `IMFramesCall.INCOMING` 加字段；`IMCallEngineListener.onCallReceived` 加 `inviter`（放在 `caller` 之后，
  **直接改签名、不留旧重载**）；`IMEventDispatcher` 透传；`IMKitListener` 透传给 reducer 与宿主。
- Kit：`IMCallViewState.inviter` + 新增 `incomingFromUid`（`inviter` → 格子里第一个人 → `peer`）；
  来电横幅 `IMIncomingBanner` 与来电页 `IMCallView.renderAudio`（**仅 INCOMING 阶段**）都改用它。
  **摆格子、`state.caller`、选人页的 `callerUid` 一律没动。**
- Demo `DemoSession` 与 `JavaApiCheck` 同步改签名。
- 一致性向量：本仓代码不用改（`VectorMatch` 是递归子集比对，回调 args 多一个键不算错，只有**条数**必须对上）；
  主会话在 `call_fsm.json` 新增了两条 inviter 用例，本仓 `:call-engine:testDebugUnitTest` 带 `RTC_CONFORMANCE_DIR` 跑过。

**② 离场的发起人可以被重新邀请**（服务端去掉了 `invite_more` 对发起人的 `bad_params`，见 server current_task）：
`IMInvitePicker` 去掉「暂时无法邀请」分支（连带 `Row.Item.inCall`）；`IMCallViewReducer.incoming` 加 `selfUid`，
发起人就是自己时不给自己摆格子。注释跟改：`IMCallEngine.inviteMore`、`IMInviteContext.callerUid`、`IMCallViewState.caller`。

**验证**（①②，`test.sh` 全量没跑）：`:call-engine:testDebugUnitTest`（23 条，新增 2 条：带 inviter / 旧服务端回落）、
`:call-uikit:testDebugUnitTest`（`CallViewStateTest` 13 条，新增 1 条）、`:demo:compileDebugKotlin` —— BUILD SUCCESSFUL。

**③ 来电铃声 + 回铃音**（草图范围，不做振动/锁屏）：
- 素材 `call-uikit/src/main/res/raw/im_ringtone.mp3`（4s 循环）/ `im_ringback.mp3`（5s 周期，450Hz 1s 通 4s 断）。
- 纯判据 `IMRingRules.ringtoneFor(state, muted)`：muted / isMeeting 无条件不响，INCOMING→来电铃声，
  OUTGOING→回铃音，其余不响；单测 `IMRingRulesTest`（7 条）。
- 播放层 `IMRingPlayer`（薄包 MediaPlayer，`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，
  **不碰 `AudioManager.mode`**——那是 `IMAudioRouter` 的地盘）；焦点两代 API 同 `IMAudioRouter`，
  **2026-09-16 真机验收通过**；但**没记下机型与 Android 版本**（本仓 CLAUDE.md 要求写明），
  所以焦点两代 API 里 O+ / O- 具体走过哪一条并不确定，下次真机窗口补记一次。
- 挂载点 `IMCallKit.update()`：`state=next` 前先存 `previousPhase`，同步（不进 `main.post`）调
  `applyRingtone`，为的是抢在接听后 `IMAudioRouter.start()` 抢焦点前面把铃声焦点 abandon 掉，
  否则通话音会被系统 DUCK。`IMCallKit.stop()` 也 `ring?.stop()`。
- 配置 `IMCallKitConfig.incomingRingtone` / `ringbackTone`（`Uri?`，默认 null 用内置素材）/
  `ringtoneMuted`（默认 false），现读，同 `bannerFirst` 读法。Demo 设置页加「静音来电铃声」开关。
- `./scripts/test.sh` 全量跑过，六步全绿。**真机验收已通过**（2026-09-16）：1v1/群 来电铃声起停、
  拨出回铃音、接听/挂断/取消瞬间铃声立即停、接听后通话音量没有被 DUCK、会议房与 `onCallMissed`
  全程不响、静音开关生效。**蓝牙耳机场景仍未验**，连同机型/版本记录一起留到下次真机窗口。
跑法：`RTC_CONFORMANCE_DIR=../im-rtc-server/docs/conformance ./gradlew :call-engine:testDebugUnitTest ...`。

**同日已提交 `1ea2013`**：选人页列出全部成员、搜索框放大镜（`ic_im_magnifyingglass.xml`）。

**体量**：`IMCallEngine.kt` 599、`IMSignalConnection.kt` 600 已到硬顶，下次改这两个文件先想好拆哪块。

## 下一步

1. 用户真机自测（服务端先重启）：发起人挂断后被邀请回来能响铃、接听，来电横幅不出现自己的格子，
   且横幅 / 来电页显示的是**把你加进来的那个人**（群通话中途加邀时不是发起人）。自测过了跑 `./scripts/test.sh` 再提交。

**本端预览对齐（`startLocalPreview(): cid` + `attachLocalView(cid, view)`）没做，原因**：
现在的 `IMWebRTCAdapter` 里，本端预览用的是一个**固定常量** `PREVIEW_TRACK_ID`（不是每次生成的 cid），
与真正发布时 `IMLocalPublisher.publish()` 现生成的 cid（`local-$kind-$nowMs`）是两套不相干的 id、
在两个不同层（media 层 / engine 层）。要做成 Web/iOS 那种「`startLocalPreview()` 返回 cid，
之后一路 `attachLocalView(cid, ...)` 认到底、含发布后」，得把 cid 生成从 `IMLocalPublisher` 挪到
预览发起的更早时刻、让预览与发布共用同一个 cid，这会牵动 `IMMediaAdapter` 接口、
`IMWebRTCAdapter.attachLocalPreview` 的采集/挂载时序、以及 `IMCallKit.localPreviewView`/`wantsLocalPreview`
的整套权限门时序——是媒体层改动，而**媒体功能改了要真机验收**（CONVENTIONS §10），
这轮没有设备可用，贸然改时序风险太高。先不做，等有真机窗口再单独立项评估。

**真机验收（本轮是纯签名/命名改动，媒体与状态机逻辑未动，暂不需要重验；下一次真机窗口仍按下表走）**：
1. **M1**：group 通话下发 `chat_group_id`/`user_data`/`timeout_sec`，被叫 `onCallReceived` 与接通后 `onCallBegin` 真的带到；`call.join` 场景（frank 在另一台设备加入）`onCallBegin` 能拿到 caller/chatGroupId。
2. **M2**：`IMInvitePicker` 真机走一遍——300ms 防抖是不是真的等到停手才发请求、滚到底是否稳定触发下一页、`slow` 搜索词 10 秒后是不是真的转成失败态、`fail` 搜索词的重试按钮能不能把请求发出去。
3. **M8**：Demo 两台设备各登一个账号，A 发群通话，B 用 A 日志里的 call_id 在「加入进行中的群通话」里加入，确认不振铃直接接通；服务端配了邀请鉴权回调时验 1409 两句文案分得开。
4. 老批次真机项（forceEnd 断网/秒挂、后台重连节奏、1v1 视频细节）见 `current_task.archive.md` 与 server 仓「跨端待验」，本轮没有动它们。

**待办**：
- **`IMCallKit.notifyOutgoing` 没有带 `IMCallOptions` 的重载**（`IMCallKit.kt` 体量已经到顶，这次先省了）：宿主自己调 `engine.call(options)` 又想用 Kit 画拨出界面时，`notifyOutgoing` 目前带不出 chatGroupId，界面上加人入口会看不到候选——这类宿主目前的替代路径是 `placeCall(calleeIds, mediaType, options)` 直接经 Kit 拨出。
- 静默失败点清单：`../im-rtc-server/docs/ops/silent-failure/android.md`（逐条状态只在那里）。`ensureCapture` 缓存死 source 那条排在 server「下一步」第 2 条。
- `call-engine/build.gradle.kts` 找向量仍「逐级往上找」，会捡到上层旧克隆（web 已修同类问题）。
- `CLIENT_PARITY.md` 真机验完再改，验之前停 🟡。
- 本端预览对齐 cid（见上）——等真机窗口。

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
- `onDisconnected(code, willReconnect)`（2026-09-15 由 `(code, reason)` 改名改类型）：`willReconnect` 由 `IMSignalConnection` 当场裁决，`IMKitListener` 直接用 `!willReconnect` 判「已放弃」，不用再猜 4403。
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
