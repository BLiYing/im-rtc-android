# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-06 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**Android 的视频通路已在真机（OPPO PKD130）上跑通并逐条验过**（2026-09-06）：
远端画面、本端预览、全屏铺满、小窗吸右上角、拨出中就看得见自己。
`./scripts/test.sh` 六步全绿。

「Android 一格画面都不出」前后一共**四个独立根因**，前两个是上一轮修的媒体通路，
后两个是这一轮真机才炸出来的渲染层：

| # | 症状 | 根因 |
|---|---|---|
| 1 | 别人看不见我、也听不见我 | 本端 Track 的 id 是 `"audio-$cid"`，而服务端按 msid 第二段（= track id）认领 m-line（协议 §3.2） |
| 2 | 协商全通却一格不出 | `onAddTrack` 拿 **stream id** 当 uid，而服务端给所有下行轨道用同一个常量 stream（`im-rtc`）。补了 `claimRemoteTracks` |
| 3 | **自己和别人的画面都不出** | `SurfaceViewRenderer.init` 头一行是 `ThreadUtils.checkIsOnMainThread()`，而 Engine 的方法跑在自己那条单线程上，`IMExecutorScheduler` 又把异常吞掉记一行日志——**渲染器一次都没初始化成功** |
| 4 | 只有本端预览不出 | `onLocalMediaStarted` 要拿前台 Activity，而通话页一起来宿主那个就 pause 了，`IMActivityTracker.foreground()` 刻意不认自己家的通话页 → 返回 null → `startLocalPreview` **一次都没被调用过** |

这一轮还做了：

| 项 | 落点 |
|---|---|
| **只采集不发布**（拨出中就看得见自己） | 预览与推流共用一个 source、两条 track（推流那条 id 必须是 cid，而 cid 要进房才生成）。接采集前先查 CAMERA 权限，不然会抢在权限门前面开摄像头 |
| **切后台自动暂停本端视频** | `IMActivityTracker` 数 started 的 Activity；进系统画中画不算切后台。回前台恢复到用户原来的选择（与 iOS 同一条规则） |
| **全屏画面真的铺满整屏** | 画面挂在根布局最底下一层（`videoFull`），头部与控制条浮在上面；inset 只让开壳、不让开画面 |
| **1v1 不做发言高亮** | 绿描边 + 绿名牌只留给九宫格 |
| 小窗吸角 | **容器没量出来时不吸**——算出来的「右上角」会退化成 x=0，正好压住左上角那颗「小窗」按钮 |
| 悬浮球红键 | 挪到**底部居中**（球吸到边上时右上角那颗有一半在屏幕外） |
| 系统画中画 | **整个撤掉**（v3.2）：那颗叉是系统画的、删不掉，按下去「小窗没了电话还在」。收起统一走应用内悬浮球 |

**这一轮（2026-09-06 下午）**：真机复现并修掉**控制条整块跑到屏幕最上面**——
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

## 下一步

- **真机验收剩下的那些**（清单见交互稿 **v3.1 §09 的 25 条**，Android 还要加 §08 的六条）：
  视频通路 / 全屏 / 小窗吸角 / 拨出中预览**已验**；还没验的是权限说明卡与「再劝一次」
  （本机权限早就授过，要 `adb shell pm revoke com.imrtc.demo android.permission.CAMERA` 再走一遍）、
  返回键收小窗、小窗长按拖动 / 互换、加号格与选人、占位格终局、
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
