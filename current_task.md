# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-06 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

**按三端真机联调日志（2026-09-06 08:00–08:50）修根因**，`./scripts/test.sh` 六步全绿。
**真机仍未验**：下面每一条都是「编得过 + 纯逻辑有单测」。

**「Android 一路没有视频」是两个独立的媒体层 bug**，服务端日志把它们分得很清楚：

| 方向 | 症状 | 根因 |
|---|---|---|
| 上行 | 别人**一格画面都没有、也听不见声音**；服务端日志里只有一行 DEBUG「上行 Track 先于 room.publish 到达，先攒着」，之后再无下文 | 本端 Track 的 id 是 `"audio-$cid"` / `"video-$cid"`，而服务端按 **msid 第二段（= track id）** 认领 m-line（协议 §3.2）。改成 track id 就是 `cid` |
| 下行 | 协商全通、`auto_subscribed` 也对，**却一格画面都不出** | `onAddTrack` 里拿 **stream id** 当 uid，而服务端给所有下行轨道用的是同一个常量 stream（`im-rtc`）——所有人共用一把钥匙。补 `IMMediaAdapter.claimRemoteTracks`（iOS / Web 早就有），轨道按 track_id 收着、归属到了再挂 |
| 本端预览 | 拨出中的小窗是空的 | `startLocalPreview` 早于 `publish` 时永远接不上采集轨道；publish 里补挂一次 |

**界面（与 iOS / Web 同一份稿 v3.1）**：

| 症状 | 落点 |
|---|---|
| 通话中第三个人打进来会把当前通话拆掉 | `CallStateMachineRecv.isForAnotherCall` + 新回调 `onCallMissed` |
| 群通话里被叫只看到两格 | `call.incoming.callee_ids` 上抛 → `IMCallViewReducer.incoming` 摆占位格 |
| 九宫格与 iOS 不是一个样子（竖屏两人是两条细长条） | `GridLayout.spec` **不能带权重**——带了的话算出来的正方形边长当场被摊没；整块 `Gravity.CENTER` 居中；容器量出来之后 `onLayout` 补摆一次 |
| 标题压在状态栏的时间电量上 | Activity 全屏边到边（不引 androidx）+ `IMCallView.onApplyWindowInsets` 让开系统栏 |
| 画中画上的「静音」点了不生效 | 去掉那个 `RemoteAction`，只留挂断 |
| 小窗没法直接挂断 | 悬浮球右上角加一颗 22 的红色挂断 |
| 两端都关摄像头时小窗消失 / 小窗入口两处 / 呼叫页标题重复 | 与 iOS / Web 同一批改法 |

**上一轮（2026-09-06 早些时候）**：UIKit 按设计稿 v3 落地——19 个由稿里同一份路径生成的
VectorDrawable、权限门（多「再劝一次」那一屏）、系统画中画、返回键收小窗、选人 Dialog。

## 下一步

- **真机验收本轮的每一条**（清单见交互稿 **v3.1 §09 的 22 条**，Android 还要加 §08 的六条）：
  **先验「视频到底通没通」**（本轮两个根因都在媒体层，纯 JVM 单测碰不到），再验权限说明卡 /
  再劝一次 / 去设置、系统画中画进出与挂断动作、返回键收小窗、小窗长按拖动 / 互换、
  加号格与选人、占位格终局、切后台、全屏与状态栏避让。
  首台验收机是 OPPO PKD130（ColorOS，后台限制最严的那一类）。
- **本端预览仍要等进房发布之后才有画面**（Engine 在进房时才起采集）：拨出中右上角的小窗是空的。
  本轮修的是「publish 之后预览接不上」，**「拨出时就看见自己」还得给 Engine 加
  「只采集不发布」的路径**，属媒体层一刀。
- **切后台自动暂停本端视频**（交互稿 §03）Android 侧还没做：进画中画时采集照跑；不在画中画而切后台的场景要补
  `closeCamera` / 回前台恢复。
- 悬浮球拖到底部 = 挂断（交互稿 M2）没做；全屏来电 `fullScreenIntent`（差异 5）属推送阶段，MVP 不做。
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
- **`GridLayout.spec` 不能带权重**：带了的话剩余空间会摊到每一格上，正方形边长当场失效，
  竖屏两个人就变成两条又高又窄的长条。
- **渲染器要按 uid 整通复用**（`IMCallKit.remoteViews`）：`engine.attachView` 会释放上一个渲染器，每次刷新都要新的话画面闪。
  小窗压在全屏画面上要 `setZOrderMediaOverlay(true)`——两个 SurfaceView 叠放默认谁在上面是不定的。
- **在系统画中画里形态仍是 FULLSCREEN**（`inSystemPip`），否则宿主界面恢复时会再挂一个悬浮球。
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
