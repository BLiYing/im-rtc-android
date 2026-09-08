# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**关 Wi-Fi 再打开，上行永远协商不回来（2026-09-08 修）**，`./scripts/test.sh` 六步全绿。

真机现场：alice(Android) 呼 carol(iOS) 视频，Android 关 Wi-Fi 再连上。
信令恢复了、下行也恢复了，**上行再也没协商过一次** —— carol 全程看不到 alice，
本端停在「正在重连」。关摄像头再打开，carol 收起头像却依然黑屏
（`room.mute` 不经过协商，服务端照常广播 muted=false，可一个包都没有）。

根因在 `IMPeerConnections` 的**协商闸门**：`negotiating` / `pendingOffer` /
`pendingIceRestart` 是三个裸 `mutableSetOf`，被三个线程并发读写 ——
信令线程（`restart_pub_ice`）、WebRTC 信令线程（`onSetSuccess` 里放闸）、
PC observer 线程（ICE 进 FAILED 时重启）。闸门一旦卡住，那条 PC
**从此永远「协商进行中」**，后续任何 offer 只排队、永不发出，而且一条错误都没有。

日志坐实：`09:16:53.534 会话已恢复，重新协商上行` 紧跟
`pub 协商进行中，offer 排队` —— 而上一次成功协商在 09:15:48.347，
中间没有任何 createOffer。闸门就是在那之后卡住的。

| 改动 | 为什么 |
|---|---|
| 抽出 `IMNegotiationGate`，一把锁包住三个集合 | 根因；顺带让它能被纯 JVM 单测覆盖（`call-engine-webrtc` 其余代码都要 libwebrtc） |
| **每一个终局都放闸**：createOffer/setLocal/setRemote 失败、以及「answer 来晚了丢掉」那条分支 | 原先只有成功路径放闸，少放一处就是永久卡死且无报错 |
| **恢复时重置在飞状态**（`resetInFlight`） | 换了连接，旧 offer 的 answer 永远回不来了。**这条是确定性修复**，不依赖竞态诊断是否准确 |

**没做 / 已知限制**：

- **竞态是推断，没能证明。** 我写了并发用例，它确实能在无锁时抓到「两个 offer 同时放行」，
  但抓不到「`negotiating -= pc` 丢失」那一种。真机上闸门到底怎么卡住的，日志答不了。
  好在 `resetInFlight` 那条是**构造上正确**的：无论怎么卡住，恢复后都能重新协商。
- **「正在重连」那条橙条没能复现。** `handleHelloOk` 无条件抛 `onConnected`，
  Kit 收到就把橙条拨回 OK，代码路径看着是对的；日志也显示恢复后候选照常上报（房间已回 joined）。
  **这一条需要一次带日志的新复现**才能定位。
- 本轮**没有真机复验**。


**1v1 视频里点小窗必崩，已修 + 真机复验（2026-09-07）**，`./scripts/test.sh` 六步全绿。

崩在 `IMFloatingBubble.setVideoView`：`java.lang.IllegalStateException: The specified
child already has a parent`（`adb logcat -b crash`，同一条栈两次）。

远端渲染器是**一个 uid 一份、整通复用**的（`IMCallKit.videoViewFor` 缓存在 `remoteViews`），
点小窗那一刻它还挂在全屏页的格子上，而小窗直接 `addView`——**没先从原父容器上摘下来**。
旁边两个容器都做了这件事（`IMVideoTile.setVideoView`、`IMCallGridView`），只有小窗漏了。

顺手补的第二个洞：`mountBubble` 挂在 `applyPresentation` 上，**每次状态更新都会跑**，
而时长每秒走一格——原先每秒把渲染器摘一次挂一次，`SurfaceView` 的 surface 跟着销毁重建。
判重那行（`IMVideoTile` 早就有）就是防这个的。

**真机复验（OPPO PKD130 + web demo 的 dave 当对端，合成音视频源）**：接通 → 点小窗 →
小窗里画面正常 → 展开回全屏 → 再收起，`crash` 缓冲区全程为空、进程号不变；
小窗稳态 8 秒内 surface 创建/销毁 **0 次**（不判重的话这里该是每秒一轮）。

**没加单测，是拍板不加**：这条是纯视图层行为，本仓只有纯 JVM 单测，钉不住
「addView 前先摘父」这类断言，而引 Robolectric 会把「一条命令、无设备、秒级」磨掉。
**结论是视图层也走真机**，规则改写进 `CONVENTIONS.md` §10 那条红线里（含判重那半）——
下次再有人想加容器，照抄现有三个即可，别再重新讨论一遍要不要 Robolectric。

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
