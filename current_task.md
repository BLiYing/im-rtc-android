# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**「呼叫中按的静音会丢」——真机 2026-09-09 抓到的隐私问题**，
`./scripts/test.sh` 六步全绿、183 条用例。分支 `fix/mute-before-publish`。**未真机复验。**

alice（Android）发起群呼，在「正在呼叫…」阶段按了静音，bob 接通后**照样听得见她说话**，
而 alice 界面上写着「已静音」。日志：

```
00:09:59.7  call.invite
00:10:01.0  WARN 没有 audio Track 可以开关   ← 按静音，被丢掉
00:10:04.8  call.connected
00:10:05.0  room.publish ×2                ← 这时才发布轨道，默认开着
00:10:05.0  WARN 没有 audio Track 可以开关   ← onRoomJoined 的补救也失败
```

**根因：`setMuted` 把两件事绑死了。** 关本端轨道不需要 `track_id`（那是服务端分配的），
只有 `room.mute` 帧需要；而原先拿不到 track_id 就整个早退，**连本端也不关**。
偏偏「拿不到」正发生在最该静音的时候。Kit 的 `toggleMic` 又是无条件翻界面的，于是界面撒谎。

`onRoomJoined` 那道补救方向对、时机错：它跑在 `room.join.ok`（05.013），
而 `room.publish.ok`（05.04）还没回来，`publishTrackIds` 仍是空的。

**改法三条**：

1. `setMuted` 拆开——本端 `media.setMuted` **无条件执行**，帧只在有 track_id 时发；
2. 意图存进 [IMMuteBook]，`room.publish.ok` 拿到 track_id 那一刻补做（本端 + 帧都补，
   因为轨道是刚现造的、默认开着）；判据是 **track_id 从无到有**，不是「表里有」，
   否则每帧下行都会重发一遍；
3. 通话结束时意图跟着作废，别漏到下一通。

界面撒谎那条**不用单独修**了：`setMuted` 现在总能落地意图，界面就不再是谎话。

**只有 Android 中招**：iOS 与 web 的 `setMuted` 都是先无条件 `media.setMuted`、
guard 只挡信令帧，所以对端听不见你——这也正好解释了真机上的不对称
（bob 在 web 静音生效，alice 在 Android 不生效）。

**顺带拆了两刀**（体量门禁 621 > 600，且明令不许放宽阈值）：
静音这一摊抽成 `IMMuteBook`（纯逻辑、可单测），协商帧转发抽成
`IMNegotiationFrames.kt` 的自由函数（它不碰门面任何状态）。`IMCallEngine.kt` 回到 587 行。

**新增 5 条用例**（`MuteBeforePublishTest`）。把 `setMuted` 注回旧逻辑，其中 2 条立刻红。

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
