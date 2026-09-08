# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点
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


**网络一直不回来时通话再也退不出去，已修（2026-09-08）**，`./scripts/test.sh` 六步全绿。
**未真机复验。**

真机现场是 iOS 那一侧：断网后停在「正在重连」，**不接网就一直停在通话界面，挂断也无效**。
Android 同形，四端都一样 —— 本地放弃的**唯一**入口是「重连上了但 `resumed=false`」时的
`synthesizeNetworkEnd`，它要求先连回来；网络不回来那一刻永远不会到。
而挂断只产出一帧发不出去的 `call.hangup`，本地状态按 §4.2 铁律 1 一动不动，所以点了没反应。

改法：连接层起一条倒计时，断开超过**上界**就抛 `onSessionUnrecoverable`，
状态机走与 `resumed=false` 完全相同的那段（房间归零 + 本地合成 `ended{network}`）。

**上界怎么来的（不能拍脑袋取 30 秒）**：服务端那 30 秒不是从我们断开算起，
是从**它自己察觉**算起，而它要连续 3 个心跳周期收不到东西才察觉（§1.3）。
所以最晚是 `断开 + 3×ping + 30s`，默认心跳 15 秒即 75 秒，再加 5 秒余量。
**取短了会杀掉一通还能恢复的电话** —— 真机 11:37 那次断开 14 秒后重连成功、通话照常继续。

三条规矩各有用例守着，都验过回退即红：会到 / 不早到 / 重连一直失败不许把截止时刻往后推
（最后这条尤其要紧：每次失败都重排的话，退避封顶 30 秒 < 80 秒，它**永远不会响**）。

**没做**：「离线时按挂断也立即收场」这一半**按拍板延期**。它要额外处理「网络在窗口内
回来了、而本地已经退出」那种幽灵成员，得在重连后补发一帧 `call.hangup`。

**关 Wi-Fi 再打开的两个故障，都已修（2026-09-08）**，`./scripts/test.sh` 六步全绿。

真机现场：alice(Android) 呼 carol(iOS) 视频，Android 关 Wi-Fi 再连上。

### ① 上行永远协商不回来 —— 已修并**真机复验通过**

信令恢复了、下行也恢复了，**上行再也没协商过一次**，carol 全程看不到 alice。
根因在 `IMPeerConnections` 的**协商闸门**：`negotiating` / `pendingOffer` /
`pendingIceRestart` 是三个裸 `mutableSetOf`，被三个线程并发读写 ——
信令线程（`restart_pub_ice`）、WebRTC 信令线程（`onSetSuccess` 里放闸）、
PC observer 线程（ICE 进 FAILED 时重启）。闸门一旦卡住，那条 PC
**从此永远「协商进行中」**，后续任何 offer 只排队、永不发出，而且一条错误都没有。

改动：抽出 `IMNegotiationGate`（一把锁包住三个集合，顺带能纯 JVM 单测）·
**每一个终局都放闸**（原先只有成功路径）· **恢复时重置在飞状态**（`resetInFlight`）。
最后这条是**构造上正确**的，不依赖竞态诊断是否准确。

真机复验（09:45 那通）：`会话已恢复，重新协商上行` → `pub 重启 ICE` → `↑ room.offer`
→ `pub ICE 状态：CONNECTED`，iOS 那侧画面恢复。修复前这里只会打印「offer 排队」。

**竞态本身仍是推断**：并发用例能在无锁时抓到「两个 offer 同时放行」，
抓不到「`negotiating -= pc` 丢失」那一种。

### ② 「正在重连」橙条永远撤不掉 —— 已修并**真机复验通过**

上一轮没能复现，这一轮从代码里找到了，与信令层无关：`IMCallView.renderBanner` 的
`if (text.isNotEmpty() || connection != OK) banner.apply(text)` **恰好漏掉了
「恢复成 OK」这一格** —— 那一刻文案是空串、connection 又正好是 OK，条件为假，
`apply("")` 一次都不会调。于是橙条停在「正在重连…」，而且**怎么操作都撤不掉**
（每次重渲染都落到同一个假条件上）。

日志坐实这跟连接层无关：09:45:14.754 `已连接 resumed=true`，之后 `sys.ping` 每 15 秒
一路到 09:52 从没断过，`onConnected` 抛过、没有任何后续 `onDisconnected`。

判断挪出 View 成了纯函数 `IMBannerRules.next`（`BannerRulesTest` 覆盖，回退即红）。
**iOS 与 Web 在同一处都是对的**（iOS 的 `else if !poor`、Web 的声明式渲染），只有 Android 有这个洞。

真机复验（PKD130，`cmd wifi set-wifi-enabled` 关 45s 再开）：断网期间截图有「正在重连…」，
开 Wi-Fi 40 秒后（`resumed=true`）截图**橙条已消失**，再等 25 秒仍然没有。修复前这一格永远撤不掉。

### ③ 小窗视频时不时黑一下 —— 已修，**未真机复验**

`room.active_speakers` **包含本端自己**，而本端音量往往就是最大的那个
（真机 10:50 那一通：alice 45 / carol 36，两人交替领先，一秒好几次）。
悬浮球原先写 `speakingUid.ifEmpty { members.keys.first() }`，跳到本端 uid 时
就拿它去要一块远端画面：渲染器照样造得出来、`attachView("alice", …)` 也挂得上，
**可本端根本没有远端轨道，那块画面永远是黑的**；而且每跳一次就换一个 view，
`videoHost` 摘一次挂一次，`SurfaceView` 的 surface 跟着销毁重建 —— 就是那一下下的黑。

`ifEmpty` 挡不住这一类：uid 不是空的，只是**不该拿来找远端画面**。
改成 `IMCallViewState.videoSpeakerUid()`，只在 members（不含自己）里挑。
九宫格拿 `speakingUid` 画绿描边是安全的（描边只画在 members 的格子上），所以只改悬浮球这一处。

### ④ **仍未解决**：收小窗再展开，远端视频回不来

同一通里按返回键收成悬浮球：**悬浮球是纯黑**（计时器在走、挂断键正常）；
点开回全屏，carol 那一路**仍然全黑**，本端小窗正常，顶部没有橙条。
这期间服务端一直在转发（5 秒 1873 包），所以是**客户端渲染侧**，不是媒体面。

这一条本来就在下面「七项默认已验、没实机走过」名单里（「返回键收小窗」）——
**那个「默认通过」的假设被证伪了**。

**③ 的修复有没有连带修掉它，不知道**：如果当时球里挂的是那块「本端 uid 的黑渲染器」，
carol 的渲染器就是无父状态，展开时按理该能挂回格子去。**读代码没读出必然的因果**，
`IMVideoTile.setVideoView` 的摘父 / z-order 顺序都是对的。**需要一次带日志的新复现**。

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
