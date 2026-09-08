# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**会话没了却不给宿主收场信号（2026-09-08）**，`./scripts/test.sh` 六步全绿。
分支 `fix/parity-room-left`（worktree `../wt-android-parity`，**叠在 `fix/code-review-0908` 之上**）。
**未真机复验。**

这一条是 **Web 那轮 `/code-review high` 的跨端对账**查出来的，**三端同源**，本端也中招。

`IMRoomMachine.resume(ctx, resumed = false)` 只是把房间清成 IDLE，**一个事件都不抛**。
有 call 的场合还有 `onCallEnd(network)` 兜着（不变量 I8），可**会议是直接 joinRoom 的、
压根没有 call**：房间机悄悄回了 IDLE，而界面还显示着「会议中」、计时器还在走，
用户完全不知道自己已经掉出去了。更要命的是一个结束类回调都没抛 → 门面的 leave 那组回调
不命中 → `media.stop()` 永远不调用，**摄像头与前台服务一直开着**，
上一轮的 PeerConnection 还会被带进下一次进房。

改法：`IMEngineMachine` 抽出 `dropLostSession`（`handleHelloOk` 的 `resumed=false` 分支与
`session_unrecoverable` 共用它）——有通话就抛 `onCallEnd`（**唯一出口，不再补 onRoomLeft**，
否则宿主记两遍账），没通话但在房里就补一条 `onRoomLeft`。
Web 的 `engineMachine.dropLostSession`、iOS 的 `IMEngineMachine.dropLostSession` 是同一段。

**新增用例 5 条**（`statemachine/LostSessionTest.kt`）：会议的两条收场路径
（`resumed=false` 与 `session_unrecoverable`）、有 call 时不重复抛、
idle 时不凭空抛、`resumed=true` 一个字不变（一致性向量
`reconnect_not_resumed_synthesizes_call_end` 钉住的那条行为没动）。

---

**拆 `DemoSession.kt`：598 → 490（2026-09-08）**，`./scripts/test.sh` 六步全绿。

它一直卡在 598 / 上限 600，**再加一行就是 FAIL**，每次提交都报 WARN。
拆出去的是两块与会话生命周期无关的东西：

| 新文件 | 装什么 |
|---|---|
| `DemoRecords.kt` | `DemoRecord` + `DemoRecordStore`（通话记录的 JSON 存取）。想读「登录到底怎么走」的人，不该先翻过一整段 JSON 拼装 |
| `DemoFormPrefs.kt` | 登录表单「上次填的东西」与默认值/提示语，一行都不碰引擎。`SharedPreferences` 的键一并移到文件级（两边都要用） |

调用点：`DemoSession.defaultServer` 之类改成 `DemoSession.form.defaultServer`（7 处，
都在 `DialerScreen`）；`DemoSession.Record` 改成顶层 `DemoRecord`（3 处，`HistoryScreen`）。

**没继续拆到预警线（480）以下**：剩下最大的一块是 `HostListener`（~110 行），
但它碰了 `DemoSession` 的 **7 个 private 成员**（`pending` / `notifyChanged` / `main` /
`Meta` / `relogin` / `prefs` / `loginGeneration`）。搬到独立文件就得把这些全改成 `internal`
——**拿封装换行数，不划算**。490 距硬闸还有 110 行，够用；真想清掉 WARN 再单独议。

---

**code review 的三条（2026-09-08）**，`./scripts/test.sh` 六步全绿、173 条用例
（engine 97 / webrtc 9 / uikit 41 / demo 26）。分支 `fix/code-review-0908`
（worktree `../wt-android-review-fixes`）。**未真机复验。**

### 1. 迟到的关闭事件会把一条好端端的连接拆掉（最重的一条）

`closeAndReconnect` **自己先调一次** `handleClosed`，而 transport 的 `onClosed` / `onFailure`
随后**还会再调一次**——`transport.close()` 只是发个关闭帧，OkHttp 一定还会回调，
中间没有任何「已经收过场了」的闩。

网络假活时（也就是心跳超时那条路）第二次回调可能**晚到好几分钟**，那时新连接早已连上：

```
心跳超时 → closeAndReconnect 就地收场 + 排重连 → 1s 后重连成功、connected=true
   ⋯ 几分钟后 ⋯
旧 socket 的 onFailure 终于冒出来 → handleClosed 又跑一遍：
  wasConnected=true → 多抛一条假 onDisconnected（界面写「正在重连」而连接好好的）
  failAll          → 把新连接上在飞的请求全掐掉
  connected=false  → scheduleReconnect → openSocket 开出**第二条 socket**
  同 uid 同 device_id → 服务端按顶号踢掉一条 → **假的 onKickedOut(TAKEN_OVER)** → 用户被踹回登录页
```

改法：**认代际**。每开一条 socket `generation += 1`，`TransportListener` 带着自己那一代，
`handleClosed(code, reason, from)` 只认没收过场的那一代（`closedGeneration` 是闩）。
`onOpen` / `onText` 也一并挡掉旧代——旧 socket 上迟到的帧是上一条会话的东西，不能喂进状态机。
`stop()` 顺手把当前代闩上，免得 logout 之后那条回调又排一次重连。

> iOS 不会踩：`IMURLSessionWebSocket` 有个 `closed` 标志，保证每条 socket 只回一次 onClose。
> 这里等价的做法就是认代际。

### 2. offer 抢在 `setLocalDescription` 前面上线路

`createOffer` 的 `onCreateSuccess` 里，`setLocalDescription` 还是异步的（回调在 WebRTC 的
signaling 线程上），紧挨着就 `events.onLocalSdp(...)` 把 offer 发出去了。局域网 / 本地 SFU 下
`room.answer` 几毫秒就能回来，而本端描述可能还没设上：`applyRemoteSdp` 看到
`signalingState()` 是 STABLE 而不是 HAVE_LOCAL_OFFER，就把它当重复应答**丢掉并 `abortOffer`**。
**这一路发布就此协商不出去**——对端看得见人、收不到流，一条报错都没有，
而且没有任何东西会重新驱动它（要等 ICE 进 FAILED 才有下一次机会）。

改法：`onLocalSdp` 挪进 `setLocalDescription` 的 `onSetSuccess`，失败那支放闸。
`LocalSdpObserver` 因此没人用了，一并删掉（别留死类）。
**iOS 与 Web 本来就是 await 完才发的**，这里是对齐它们。

### 3. `resume` 无条件把 `reconnecting` 推成 `joined`

`disconnected` 会把 `JOINING` 也推进 `RECONNECTING`，而那次 `room.join` 还在飞、
服务端从没受理过我们。恢复后本端以为在房里 → 每帧换回 1201/1203，
重新 join 又因「不在 idle」拒 2005。

本端目前靠 `onRequestFailed` 的 `join_failed` 能兜住（`failAll` 是同步回调，
排在 `onDisconnected` 前面），**但那是时序凑巧**——iOS 同一段代码就因为多两跳 actor 翻过车。
改法：房间上下文加 `didJoin`（只由 `room.join.ok` 置位），`resume` 据它分辨来路：
真进过房才回 `JOINED`，否则**重发一次 `room.join`**（房号房票都在手上，攒下的意图照旧留着）。
**三端同一份，不依赖谁先谁后**（iOS 同轮一起改）。

**向量没动**：两条 reconnect 向量的初始态都是 `room: joined`，`didJoin` 不影响它们。
向量跑法里补了一句种子——**是种子不完整，不是实现变了**。

**新增 10 条用例**：`StaleSocketCloseTest`（4 条，`FakeTransport` 现在留下每一条 socket 的
listener，才测得出「旧的迟到」）、`RoomResumeTest`（6 条）。

## 下一步

- **「没帧了就露头像」还要不要加本端兜底（②）** —— **①（服务端）2026-09-06 夜已落地**：
  SFU 接了发布侧 PC 的 `OnConnectionStateChange`，判死后**替他把音视频轨道标 `track_muted`**
  （不是原计划的 `track_unpublished`——复用「他关了摄像头」这条，可逆、五端零改动；
  unpublish 会把轨道记账一起拆掉，人回来还得重新协商）。判据用 `failed` 不用 `disconnected`，
  否则地铁里格子会闪个不停。
  剩下的决定是要不要再加 ②：**服务端也判不出来的那种**（PC 还活着、就是不发帧了）现在仍是纯黑。
  做法照旧——远端轨道挂个只记时间戳的 `VideoSink`，Kit 借现成 1s 计时器判「N 秒没帧」，
  落点是 `IMMediaAdapter` 加**查询方法**（不是新回调，不动 §7.5 五端契约）；缺点是 iOS/Web
  不跟就成了三端不一致。**这是产品行为不是 bug 修复，拍板之前不要动手。**
- **真机验收清单已按「默认通过」收口**（交互稿 v3.1 §09 的 25 条 + §08 的六条）。
  仍未实机走过、发现问题再回头的有七项，见「已知坑」末条。
- 悬浮球拖到底部 = 挂断（交互稿 M2）**拍板不做**；全屏来电 `fullScreenIntent`（差异 5）属推送阶段，MVP 不做。
- 「只引 Engine 自画 UI」的示范、日志回传汇入时间轴仍是 ⬜（见 CLIENT_PARITY）。

## 已知坑 / 限制

- **真机连不上服务端，先看链路再看代码。** 两条路二选一，且**跟机器走、不跟仓走**：
  局域网 IP（PKD130 可用）或 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`
  （**Pixel 2 XL 只有这条**：它与 Mac 挂在同一 SSID 的两个不同 AP 上，互相 ARP 不到；
  它 ping 得通路由器和 iOS 真机，所以**不是**「隔离所有客户端」）。
  `adb reverse` **拔线/重插/`adb kill-server`/手机重启就没了**，而 Demo 记在 prefs 里的地址还在——
  症状是启动即 `Failed to connect to /127.0.0.1:8787`，**看着像登录 bug，其实是隧道掉了**。
  判据：手机 `ping` 得通 Mac 且 Mac 上 `arp -n <手机 IP>` 有表项 → 局域网可用；否则老实打隧道。

- 日志回传已在 Pixel 2 XL（Android 11）上验收：登录 → 拨号 → 终局的完整链路
  都进了 `client-android-carol.log`，与服务端日志在 `timeline.py` 上合得起来。
  **`-demo-login` 之外不存在这个接收口**，生产环境里回传是空转的。

- **「人先进来、轨道后到」是常态，不是异常**：`onUserEnter` 那一刻远端视频轨道往往还没到。
  任何「摆好格子就顺手做一次」的动作（层上报、尺寸、订阅）都要能在轨道到达时再做一遍
  （现成套路是 `invalidateReportedLayer`）——层上界为此空转过整整一版，三端各踩一次。

- **`IMGrid.dimensions` 的默认 aspect 是 0.7**（竖屏手机上头部与控制条之间那块区域的形状），
  不是 0.5：按 0.5 算 9 个人会排成 2×5，与 iOS 的 3×3 对不上。真机上用的是量出来的实际比例。
- **运行时权限只能从 Activity 请求**，拨出前 Kit 未必有界面在前台——所以有 `IMPermissionActivity`（透明、不入最近任务）。
  「问过没」记在 `im-rtc-kit` SharedPreferences 里：Android 没有「未决定」这个状态可查。
- **本端 track id 必须就是 cid**（协议 §3.2 的 msid 第二段）：加前缀服务端就永远认不回来，
  上行 RTP 一直卡在「先攒着」的队列里，而日志里只有一行 DEBUG。
- **远端轨道按 track_id 认领，不能按 stream id**：服务端给所有下行轨道用的是同一个常量 stream。
  归属由信令层通过 `claimRemoteTracks` 灌进来，轨道 / 归属 / 渲染器**三者到达顺序完全不定**，
  统一在 `bindRemoteTracks` 里判重与换绑。
- **`SurfaceViewRenderer` 的 `init` / `setEnableHardwareScaler` / `setScalingType` 只能在主线程调**
  （头一行就是 `ThreadUtils.checkIsOnMainThread()`）。Engine 的方法在自己那条单线程上跑，
  而调度器会把异常吞掉——**渲染器初始化失败是没有声音的**，症状只有「画面全黑」。
  渲染相关的一切（含轨道 / 归属 / 渲染器三张表）统一在 `IMWebRTCAdapter.onMain` 里。
- **前后台判定只认「我们亲眼看见 started 过的界面」**（`IMForegroundState`）：钩子是登录后才装的，
  宿主首页的第一次 `onStart` 我们没见过——它的 `onStop` **不能**算成「App 进后台」。
  代价是装钩子后用户第一次按 Home 不报后台（那时 phase 还是 IDLE，无影响），下一次 `onStart` 起就准了。
  **任何「按数量判前后台」的写法都会重蹈覆辙**，因为 SDK 永远是半路装上去的。
- **`SurfaceView` 一息屏就没**：Surface 被销毁，而 libwebrtc 的 `EglRenderer` 只在下一帧
  到达时才画、**不会重画上一帧**。所以「回到前台后某格是纯黑」永远意味着**那个对端不发帧了**，
  不要往渲染器上查。iOS 的 `CAMetalLayer` 会留住旧帧，所以同一个故障在两端长得不一样。
- **`IMActivityTracker.foreground()` 拿不到通话页**（它刻意不认 `IMCallActivity`，横幅不该盖自己）。
  凡是「通话中要一个 Context」的地方一律用 `appContext`，别指望前台 Activity。
- **小窗吸角要等容器量出来**：`IMPipLayout.origin` 是拿容器宽高算的，宽是 0 时右上角退化成 x=0。
- **前台服务类型不能降级**：预览可能先把摄像头开起来了，`start()` 再传 `withCamera=false`，
  Android 14 起就是「正在用摄像头却没有 camera 类型」。
- **横排里的占位格高度必须写死 0**：裸 `View` 用 `wrap_content`，`View.getDefaultSize` 对
  `AT_MOST` 直接返回 specSize（= 整块可用高度），一个看不见的占位格就能把控制条撑到整屏高，
  贴底重力失效、按钮整体跑到屏幕顶上。同类坑对任何「用裸 View 占位」的地方都成立。
- **`GridLayout.spec` 不能带权重**：带了的话剩余空间会摊到每一格上，正方形边长当场失效，
  竖屏两个人就变成两条又高又窄的长条。
- **`GridLayout` 会偷偷把 `spec(UNDEFINED)` 改写成具体行列下标**（每次 measure 走
  `validateLayoutParams()`）。于是「把 `columnCount` / `rowCount` 往小改」在有子视图时
  **必然抛 `IllegalArgumentException`**——除非先把每个子视图的 spec 退回 `UNDEFINED`
  再改（`IMCallGridView.apply`），或者先 `removeAllViews()`。
  而列数**同一批人也会变**（第一轮按默认 aspect 0.7 估、量到真尺寸是 0.48），所以这条不转屏也踩得到。
- **渲染器要按 uid 整通复用**（`IMCallKit.remoteViews`）：`engine.attachView` 会释放上一个渲染器，每次刷新都要新的话画面闪。
  小窗压在全屏画面上要 `setZOrderMediaOverlay(true)`——两个 SurfaceView 叠放默认谁在上面是不定的。
- **悬浮球默认走应用内浮层、不申请 `SYSTEM_ALERT_WINDOW`**（CONVENTIONS §8）；离开宿主 App 就看不见，回来还在。
- **通话中必须起前台服务**（已有 `IMCallForegroundService`）；Android 14 起 `FOREGROUND_SERVICE_MICROPHONE/_CAMERA` 已声明。
- **`onDisconnected(code, reason)` 只有 4403 当「断开」**，其余关闭码都当「正在重连」（Engine 会自己回来）。
- **禁止 `org.json`**（JVM 单测里是空壳桩）；`protocol/` 与 `statemachine/` 不许 import android.*（门禁守着）。
- **离房要停媒体，且判据是「媒体还有没有人要」**（房间与通话都回 idle 才停），细节见归档。
- **「返回键收小窗」这一项已被证伪**（2026-09-08 真机）：收小窗后远端视频再也回不来，见「当前焦点」③。
  其余六项仍按「默认已验」收口，但这次说明**这个假设是有代价的**。
- **这七项按「默认已验」收口，没实机走过**（2026-09-08 拍板）：权限说明卡与「再劝一次」
  （要先 `adb shell pm revoke com.imrtc.demo android.permission.CAMERA`）、返回键收小窗、
  小窗长按拖动 / 互换、标题栏加人与选人、占位格终局、切后台暂停视频、九宫格三人以上。
  **发现问题再回头查**，不再挂着当未完成项。
- **libwebrtc 锁 M150**（`150.7871.01`），与 iOS 的 M152 对不齐是已知且可接受的；H.264 要专门跨端实测。

## 关联工程 / 常用命令

### 本机环境（2026-09-05 实测）

JDK 17（`/usr/libexec/java_home -v 17`）· SDK 到 android-36 / build-tools 36.0.0 · `adb` 在
`~/Library/Android/sdk/platform-tools/`（不在 PATH）· 两台真机：**OPPO PKD130 / Android 15**（局域网直连）与 **Google Pixel 2 XL / Android 11**（`903KPED2067148`，**只能走 `adb reverse`**）·
本机 Intel Mac（模拟器 x86_64，真机 arm64，两个 ABI 都要能出包）。

- **各端能力对照表：`../im-rtc-server/docs/CLIENT_PARITY.md`**（✅ 只写在那里，本文件不重复）。
- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。**真机的地址二选一**，见「已知坑」第一条：局域网 IP，或 `adb reverse tcp:8787 tcp:8787` 后填 `http://127.0.0.1:8787`。
- 常用命令：
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口：门禁 ×3 + 向量可达 + assembleDebug + 纯 JVM 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ```
