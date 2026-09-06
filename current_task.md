# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-06 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**「锁屏解锁后某个格子黑屏」——不是解锁弄坏的，是解锁擦掉了那张遮丑的旧画面（2026-09-06 夜）**
，`./scripts/test.sh` 六步全绿。

用户报：九宫格里锁屏再解锁，bob（iOS）那格是纯黑；iOS 端同场景没有。**根因不在渲染，在对端**：

| 环节 | 事实 |
|---|---|
| 现场证据 | 出问题那一刻抓的 bob 自己的日志里，`pub`/`sub` 两条 PeerConnection **每 5 分钟一轮 `disconnected → failed`，从 20:15 起再没回到 `connected`**。他的媒体早就断了 |
| 为什么锁屏之前看不出来 | 旧的 `SurfaceView` 里还留着**冻住的最后一帧**，看着像正常画面 |
| 为什么解锁之后是纯黑 | **息屏会销毁 `SurfaceView` 的 Surface**，解锁给的是一块空的新 Surface；而 libwebrtc 的 `EglRenderer` **只在下一帧到达时才画**（`pendingFrame` 为空就直接返回，不会重画上一帧）。对端还在发帧的格子 33ms 内就补上了，**已经不发帧的格子于是永远是纯黑** |
| 为什么 iOS 没这一幕 | iOS 用 `RTCMTLVideoView`（`CAMetalLayer`），图层内容在后台不会被丢，回来还是那张冻住的旧帧。**同样是死的对端，两端只是骗人的方式不同** |
| 为什么格子不显示头像 | `IMVideoTile.apply` 的判据是 `hasVideo`，而它只跟 `onUserVideoAvailable`（= `room.track_published/muted/unpublished`）走。**对端"悄悄不发了"没有任何一条信令**，本端于是一直以为他有画面 |

本轮只动了**能独立成立的那半**：

| 改动 | 说明 |
|---|---|
| **通话页 `FLAG_KEEP_SCREEN_ON`** | 打着电话让屏幕自己睡过去本来就不对（所有通话 App 都不这么干）。堵掉「没人碰手机、屏幕自己黑掉」这条最常见的触发路径。**它不解决用户主动锁屏** |
| **小窗 `snap()` 加「本来就在那儿就什么都不做」** | 这不是省事，是**保住日志**：`snap()` 每秒被叫好几次且参数一模一样，排查这个 bug 时 logcat 里 **109 行 imrtc 日志全是这一条**，整个 app 的历史被冲干净，只好靠 SurfaceFlinger 图层表反推。刷屏的日志比没有日志更糟。修完实测：同样一通电话从每秒一条降到**全程 1 条** |

**还没做，需要拍板的那半**（见「下一步」）：让格子在「一段时间收不到帧」时露出头像。

**上一轮：「对端看不到我的画面」——前后台记账把摄像头 mute 了（2026-09-06 傍晚）**，`./scripts/test.sh` 六步全绿。

三端联调（web alice 主叫 + iOS bob + Android ivan，呼 8 个人）报的两条，一条在本仓、一条在服务端：

| 症状 | 根因 | 落点 |
|---|---|---|
| **web 与 iOS 都看不到 ivan 的视频**（只显示头像），本机一切正常 | `IMActivityTracker` 的前后台记账是个**计数器**，而生命周期钩子是 `IMCallKit.start` 装的、宿主是**登录成功之后**才调它——那时首页早就 `onStart` 过、计数器压根没数到它。接听后通话页 `onStart`（0→1），~0.5s 开场动画放完首页 `onStop`（1→0），Kit 当成「App 切后台」，按既定规矩把摄像头 mute 掉 | `IMActivityTracker` 改记**集合**（`IMForegroundState`）：**没见过它 start 就不认它的 stop** |
| 离线的人在 Android 这侧一直「呼叫中…」（主叫侧正常） | **不是本仓的 bug**，在服务端：`call.incoming.callee_ids` 发的是原始名单，而字典序靠后的被叫会先收到前面那些人的 `call.no_answer`（那时他还 idle，四端一致地丢弃）。iOS 的 bob 排第一所以没事 | `im-rtc-server` 的 `call.calleeIDs()` 只列还没出局的人 |

**这个前后台 bug 极具迷惑性，值得记住**：`cameraOn` 这个界面状态压根没被改，
所以「关摄像头」按钮还亮着、本端预览也还在画，**坏的只有对端**（它收到 `room.track_muted{video}`）。
100% 复现，可**按一次 Home 再回来就自愈**（那一轮把首页数进去了），于是看起来还很随机。
`IMForegroundState` 是纯 JVM 的，两个用例先换回旧的计数器实现看它红过。
iOS 用 `UIApplication` 通知、Web 用 `visibilitychange`，**没有这套记账，不存在同一个洞**。

真机复验（OPPO PKD130，**force-stop 后冷启动**——正是触发条件）：alice 那侧
`userVideoAvailable{ivan,true}` 之后不再翻 false，两侧都看得到对方；Android 标题从
「群通话 · 7 人 + 5 个呼叫中」变成「群通话 · 2 人」。

**同日下午：九宫格拉齐 + 三个只在 Android 上有的坑**，`./scripts/test.sh` 六步全绿。
起因是用户三端并排看九宫格，报了五条——**其中三条是本仓独有的**：

| 症状（用户报的） | 根因 | 落点 |
|---|---|---|
| 九宫格没跟 iOS 拉齐：格子被按钮压住，三个人排成一竖条 | **`stage` 的下边界就是屏幕下边界**。控制条为了浮在全屏画面上是直接挂在根布局上的（不在 `column` 里），于是「在 stage 里居中」= 在整屏里居中；`IMGrid.dimensions` 拿到的 aspect 也从 0.68 掉到 **0.48**，连行列都算错。iOS 钉的是 `controlsStack.topAnchor`、Web 是 flex 的兄弟节点 | `IMCallView.applyStageInsets()`：给 stage 加一条下 padding（= 控制条上沿到屏幕底边），**只在语音页与九宫格加**，视频版式照旧铺满 |
| **视频一直在闪** | `layoutGrid` 无条件 `removeAllViews()` 再逐个 `addView`，而 `render` 是**每秒好几次**（计时器 1s 一跳、网络质量与主讲人都是周期帧）。格子里装的是 `SurfaceViewRenderer`——**一从 window 上摘下来 Surface 就销毁**，重挂要重建再等关键帧 | 新文件 `IMCallGridView`（与 iOS / Web 同名同职责）：没变就返回、只有尺寸变就地改 LayoutParams、只有集合变才重挂 |
| 对方拒接 / 未接听时闪过一个看不清的画面 | 复位后的状态是**全默认值**（语音 / 非群 / IDLE），而 `render` 的 `isEnded` 只认 ENDED——照常画出来就是一屏「语音通话中」（大头像 + 标题「通话」+ 静音/扬声器/挂断）。而 `IMCallActivity` 是**先 render 再 finish**，退出动画那两三百毫秒完整可见；九宫格结束时版式还会从 GRID 整个跳成 AUDIO | 两道闸：`IMCallView.render` 在 IDLE 直接返回；`IMCallActivity.render` 要关页面就不再画，并 `overridePendingTransition(0, 0)`。Web 的 `CallOverlay`、iOS 的 `IMCallWindow` 在 idle 时本来就不画 |

顺带补的（都是「查根因时发现的一直缺」）：

| 项 | 说明 |
|---|---|
| **`IMCallEngine.setRemoteLayer`** | 门面上**压根没有这个方法**，`IMGrid.layerFor` 只有单测在调，一帧 `room.update_layer` 都没发过——服务端于是按默认的 `m` 给每一路下发，九宫格里八个小格子每格都收半高清，带宽与解码器一起翻几倍，而症状只是「卡、掉帧」。Kit 侧 `IMCallKit.reportLayer` 带去重（`render` 每秒好几次，同一个值不重复发） |
| **`retireTiles` 在 Engine 侧解绑** | 原先只把 View 从格子上摘掉，`engine.attachView(uid, null)` 没调，解码器一直占到整通结束。ENDED 停留的 1.5~3s 里也顺手收掉 |
| **格子里的渲染器不再随「有没有画面」摘挂** | 对端一关摄像头就 `setVideoView(null)`，再开时要重建 Surface 等关键帧。改成一直挂着，靠 `visibility` 切（与 iOS 一致） |
| **撤掉网格里的加号格** + **3~4 格竖屏恒两列** + **远端截到 8** | 三端同一份改动，理由见 `CLIENT_PARITY` v1.5 那段（验收结论在 v1.6） |

**已在 OPPO PKD130 上验收（同日下午）**，四条都过，依据是量出来的数不是"看着像对的"：

| 验的 | 怎么验的 | 结果 |
|---|---|---|
| 九宫格版式 | 三人群通话截图**按像素量** | 第一行两格 y 385–704、第二行一格 y 721–1040，格子 **320×320**、间距 8dp；**控制条上沿 y=1200，格子最低 1040**。按修复前的几何（stage 延到 1604、aspect 0.488）老判据只能给「1 列 3 行、每格 437px、排到 y≈1587」——这组数在旧几何下算不出来 |
| 不再每秒重挂 | 10 秒每秒采一次 `dumpsys SurfaceFlinger --list` | `SurfaceView[…IMCallActivity]` 的图层 id（19904/19906/19908/19910）**十次全同**；而 2 人变 3 人时它**会**重建——两头都钉住了 |
| 收场不多画一屏 | 动画放慢 10× + 一次 adb 连拍 22 帧（≈3.3fps） | 「对方已拒接」→ **直接拨号页**，中间没有任何一帧「深色 + 控制条按钮」（那是 bug 的指纹，呼叫中页就是这个特征）。退出动画已是 0ms |
| 层上界真的发出去了 | 补了一行日志（见下）再看 logcat | `I/imrtc/engine: 层上界已报 uid=carol layer=m tracks=1`，两格 → `layerFor(2)=m` 分档正确 |

**顺带补的一条**：`setRemoteLayer` 加了 INFO/DEBUG 两支日志。**这条通路原本从外部完全不可观测**
——服务端不记录成功的 room 帧、客户端也不逐帧打日志，这正是「门面上压根没有 setRemoteLayer」
能悄悄躺好几周的原因（症状只是「画面卡」，没有任何一条报错）。

**仍没验**：5 格以上的 `l` 档与真实码率；权限说明卡那一串；小窗互换 / 长按拖动；切后台。

**同日稍早（也是这一轮的前提）**：真机复现并修掉**控制条整块跑到屏幕最上面**——
两排按钮压在标题栏与状态栏上（用户报「按钮都在上方，iOS 是对的」）。
根因不在重力也不在 inset：下排那个**占位格是裸 `View` 且高度写的是 `wrap_content`**，
而 `View.getDefaultSize` 对 `AT_MOST` 返回的是 specSize，它一个人就吃满了整块可用高度
（真机 dumpsys 量到 1372px），把下排、进而把 `wrap_content` 的 `controls` 撑到近乎整屏，
贴底的重力于是没了意义。占位格的高度改成写死 0（`IMCallView.fillRow`）。
会议房真机复验：两排按钮回到底部，挂断居中、翻转在右——与 iOS 一致。

紧接着的 `/code-review` 在同一段代码里又抓出两条，都已修并真机复验：

| 症状 | 根因 |
|---|---|
| **接听视频来电当场闪退**（走全屏来电页时） | 摄像头开关来电时在下排、接通后在上排，而 `addView` 遇到「已经有父容器」的 View 直接抛 `IllegalStateException`。`fillRow` 现在先把 View 从原来那一排摘下来 |
| 名字牌的深色底板横贯整格 | 它在下排里带着 `weight=1`，一个「我」字拖着一条通栏。改成 `wrap_content` + `onSizeChanged` 里算出来的 `maxWidth` |

> **控制条那两条是本轮「让开控制条」的前提**：`applyStageInsets` 量的是 `controls.top`，
> 而占位格没修之前 `controls` 被撑到近乎整屏、`top ≈ 0`——按它让位会把整个舞台区吃掉，
> 九宫格直接缩成零。两条必须一起在。

**上一轮（2026-09-06 上午）：Android 的视频通路在真机 OPPO PKD130 上跑通并逐条验过**——
四个独立根因（本端 track id 必须是 cid / 远端按 track_id 认领 / `SurfaceViewRenderer.init` 撞渲染线程 /
`startLocalPreview` 拿不到前台 Activity）连同「只采集不发布」「切后台暂停视频」「全屏铺满」的落地，
**已挪到 [current_task.archive.md](current_task.archive.md)**。

## 下一步

- **「没帧了就露头像」要不要做、做在哪** —— 现在一个媒体已死的对端在格子里是一块**纯黑**，
  与「他开着摄像头对着黑暗」完全一样，用户只能猜。三条路：
  ① **服务端（推荐）**：SFU 是唯一真正知道的人（它看得见对端上行 RTP 停了 / 发布侧 PC 的 ICE 失败），
     判死后 `room.track_unpublished` 一发，**三端一起自动变头像**，不用各写一遍启发式。
     现在 `internal/sfu` 压根没接 `OnConnectionStateChange`。
  ② **Android 本端帧看门狗**：给远端轨道多挂一个只记时间戳的 `VideoSink`，
     Kit 借现成的 1s 计时器判「N 秒没帧」。落点是 `IMMediaAdapter` 加一个查询方法
     （**不是**新回调，不动 §7.5 五端契约）。缺点是 iOS/Web 不跟就成了三端不一致。
  ③ 两个都做（②当兜底）。
  **在拍板之前不要动手**——这条会改「格子什么时候显示头像」，是产品行为不是 bug 修复。
- **对端 PC 断了没有人做 ICE 重启**：bob 那两条 PC failed 之后五分钟一轮地重复，
  永远回不来，通话却还"在进行中"。四端都没有 ICE restart 的代码。这是比黑屏更根的一条。

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
