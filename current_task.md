# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**会话恢复之后重新协商上行 + 红按钮永不静默（2026-09-07）**，`./scripts/test.sh` 全绿。

| 改动 | 为什么 |
|---|---|
| `IMMediaAdapter.restartPubICE()`（新）+ `IMPeerConnections.markIceRestart()` | **本仓原先是三端里唯一不对称的**：ICE 重启只活在媒体实现内部，引擎调不到；iOS/Web 的适配器早有这个方法。补齐后三端同名 |
| `RoomStateMachine` 新增 act `restart_pub_ice`（+ `ROOM_ACTS`） | 与 iOS/Web 对齐：发帧是 Engine 的事，媒体层不认识信令，也不知道此刻房间在不在 joined |
| `onConnected` 里 `resumed==true` → 重协商上行 | 协议 §1.4 写着「客户端的 pub PC 若已失效则重发 `room.offer{pc:"pub"}`」，一直没实现。只挂在「PC 判 FAILED 那一刻」是不行的——网一断信令也断，房间已是 reconnecting，动作会被拒且不进缓冲 |
| `IMCallKit.hangup()` 的 `Action.NONE` 分支不再是 `Unit` | 用户按挂断的意图没有歧义：把我弄出去。认不出该发哪种结束帧 = 本地记账已经和服务端对不上，那时唯一正确的动作是**本地收场**，不是什么都不做 |

**媒体实现里那条即时重启保留**（`FAILED` → `createOffer(iceRestart=true)`）：信令还活着时它是对的。

**没做 / 已知限制**：本轮**没有任何真机复验**——ICE 那条尤其要真的拔网线才验得了。
Android「无法挂断」的**根因未定**（Android 不上报日志到 logsink，只有 logcat），
只做了「红按钮永不静默」的兜底；服务端补发一落地，那个僵尸态本身就不该再出现了。

## 上一轮

**发起群通话当场闪退（2026-09-07 修）**，`./scripts/test.sh` 六步全绿。

`IMCallGridView.apply` 在「同一批格子、只是尺寸变了」那条路上直接改 `columnCount`，
撞上 GridLayout 的一条隐藏约定：格子是不写行列的（`spec(UNDEFINED)`），
**但它每次 measure 都会在 `validateLayoutParams()` 里把它们改写成具体下标**
（`columnSpec` 变成 `[2,3)`）。于是「在场子视图的最大下标」= 上一版的列数，
下一次把列数**调小**，`Axis.setCount` 当场抛 `IllegalArgumentException`。

**不用转屏就能撞上**：第一轮 `render` 早于第一次 layout，只能按默认 `aspect = 0.7` 估
（9 人 → 3×3）；量到真尺寸那一轮是 0.48（控制条的下 padding 还没生效）→ 2×5。
`columnCount = 2` 而在场最大下标是 3 —— 发起群通话就是这么炸的。

修法是**先把每个格子的 spec 退回 `spec(UNDEFINED)` 再改行列数**（`setLayoutParams`
会让 GridLayout 重算最大下标），一个 `SurfaceView` 都不摘、不闪。

顺带修掉同一函数里的第二个洞：「没变就不重挂」的判据原先比的是**上一次记下的 `tiles`**，
而格子会被 `pinFull` / `mountInPip` 从格子里摘走挂到全屏画面或小窗。改成比**在场的子视图**
（`childrenAre`），否则视频版式切回九宫格时会认成「什么都没变」，格子再也回不来——一屏空网格。

**没做**：真机复验。这条要在 OPPO PKD130 上真的发起一次 9 人群通话才算数，
单测只钉住了前提（`同一批人列数也会变小`，纯 JVM）。

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
- **真机验收剩下的那些**（清单见交互稿 **v3.1 §09 的 25 条**，Android 还要加 §08 的六条）：
  视频通路 / 全屏 / 小窗吸角 / 拨出中预览**已验**；九宫格那一批（在按钮上方、三人两列、
  不再重挂、拒接后不闪那一屏、层上界已发）**同日下午已验**（见上表）；还没验的是权限说明卡与「再劝一次」
  （本机权限早就授过，要 `adb shell pm revoke com.imrtc.demo android.permission.CAMERA` 再走一遍）、
  返回键收小窗、小窗长按拖动 / 互换、标题栏加人与选人、占位格终局、
  切后台暂停视频、九宫格三人以上。
  首台验收机是 OPPO PKD130（ColorOS，后台限制最严的那一类）。
- 悬浮球拖到底部 = 挂断（交互稿 M2）**拍板不做**；全屏来电 `fullScreenIntent`（差异 5）属推送阶段，MVP 不做。
- 「只引 Engine 自画 UI」的示范、日志回传汇入时间轴仍是 ⬜（见 CLIENT_PARITY）。

## 已知坑 / 限制

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
- **libwebrtc 锁 M150**（`150.7871.01`），与 iOS 的 M152 对不齐是已知且可接受的；H.264 要专门跨端实测。

## 本机环境（2026-09-05 实测）

JDK 17（`/usr/libexec/java_home -v 17`）· SDK 到 android-36 / build-tools 36.0.0 · `adb` 在
`~/Library/Android/sdk/platform-tools/`（不在 PATH）· 真机 **OPPO PKD130 / Android 15 / arm64-v8a** ·
本机 Intel Mac（模拟器 x86_64，真机 arm64，两个 ABI 都要能出包）。

## 关联工程 / 常用命令

- **各端能力对照表：`../im-rtc-server/docs/CLIENT_PARITY.md`**（✅ 只写在那里，本文件不重复）。
- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。**真机必须填 Mac 的局域网 IP**。
- 常用命令：
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口：门禁 ×3 + 向量可达 + assembleDebug + 纯 JVM 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ```
