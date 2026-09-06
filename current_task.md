# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-06 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。

## 当前焦点

**UIKit 按设计稿 v3 落地（2026-09-06）**：版式、色值、图标照 iOS 稿 1:1，系统集成层按交互稿 §08 的差异单做。
`./scripts/test.sh` 六步全绿（四模块 + Demo APK 编过，纯 JVM 用例 19 个 uikit + engine 那批）。
**真机没验**——本轮每一条都是「编得过 + 纯逻辑有单测」。

| 块 | 落点 | 内容 |
|---|---|---|
| 令牌 | `IMKitTheme.kt` | 与 iOS / Web 逐条对应：#121418 底、语音页径向渐变、danger #E5484D、accept #3DDC84、warning、九色头像板；尺寸 / 动效常量 |
| 图标 | `res/drawable/ic_im_*.xml`（19 个）+ `IMKitIcon` | **由设计稿 §05 的路径生成的 VectorDrawable**（24×24、描边 1.8、圆头），与 Web 的内联 SVG 同一份数据；**不再有 emoji、不再拿文字当图标** |
| 算术 | `IMAvatar.kt` · `IMPipLayout.kt` · `IMGrid.kt` | `fnv1a32(uid) % 9`；小窗四角吸附 / 避让 / 按容器形状选尺寸；九宫格行列跟容器形状走（`dimensions(count, aspect)`），`KitRulesTest` 钉着与 Web / iOS 同一组向量 |
| 视图模型 | `IMCallViewState.kt` | 新增 `isSwapped` / `cameraBlocked` / `connection` / `canInvite` / `hint`、成员 `accepted` / `settled` / `networkLevel`；判据：`canShowInvite`、`layout(hasLocalVideo)`、结束原因 / 网络文案与 iOS 逐字对齐 |
| 权限门 | `IMPermissionGate.kt`（纯逻辑）+ `IMPermissionActivity.kt`（透明 Activity） | 三段式 + **Android 多的那一屏**：拒绝一次再劝一次（`shouldShowRequestPermissionRationale`），第二次才「去设置」。麦克风被拒整通取消、摄像头被拒降级为语音 |
| 入口 | `IMCallKit.kt` + `IMKitListener.kt` | 新增 `placeCall` / `joinMeeting`（**先过权限门再发帧**）；`inviteMore`、`swap`；渲染器按 uid 整通复用；占位格终局 2s 后收；1202 / 1407 / 2001 分支；横幅 5s 升级全屏 |
| 通话页 | `IMCallView.kt` + `IMCallChrome.kt` + `IMVideoTile.kt` + `IMControlButton.kt` + `IMPipView.kt` | 三种版式 audio / video / grid；控制条 3s 自动隐藏 + 底部渐变；小窗长按 350ms 拖动、松手吸角、单击互换；九宫格加号格（仅主叫）；圆内图标 + 圆下文案的五态按钮 |
| Android 差异 | `IMCallActivity.kt` | **返回键 = 收进小窗**；视频通话收起 / 按 Home **走系统画中画**（16:9，带挂断 / 静音 RemoteAction），语音或不支持时退回应用内悬浮球；manifest 加 `supportsPictureInPicture` |
| 其余 | `IMInvitePicker.kt`（底部选人 Dialog）· `IMIncomingBanner`（38 圆 + 渐变头像）· `IMFloatingBubble`（视频形态 90×120 缩略）· `IMCallKitConfig.inviteCandidates` + `IMInviteCandidate` |

Demo：`DemoSession.placeCall / joinMeeting` 改走 `IMCallKit.placeCall / joinMeeting`（权限门在 Kit 里）；
候选名单传九人名单去掉自己；`JavaApiCheck.java` 补了新公开面的调用。

**`/code-review`（Web 那一轮）连带修的两条**：加人被 1407 / 1202 拒时 `revokeLastInvite()`
收回占位格（那几个人根本没响过铃，不会有 `onUserReject`）、提示走 `IMCallKit.hint()` 3s 自撤
（`HINT_HOLD_MS`；`statusText` 里 hint 优先于时长，不撤就再也看不到计时器）。
摄像头发布失败那条 Android 本来就走 `onError(2001)` → `cameraBlocked`，不用改。

## 下一步

- **真机验收本轮的每一条**（清单见交互稿 §09，Android 还要加 §08 的六条）：权限说明卡 / 再劝一次 / 去设置、
  系统画中画进出与 RemoteAction、返回键收小窗、小窗长按拖动 / 互换、加号格与选人、占位格终局、切后台。
  首台验收机是 OPPO PKD130（ColorOS，后台限制最严的那一类）。
- **本端预览要等进房发布之后才有画面**（Engine 在进房时才起采集）：拨出中右上角的小窗是空的。
  要与 iOS / Web 一致（拨出时就看见自己）得给 Engine 加「只采集不发布」的路径，属媒体层一刀。
- **切后台自动暂停本端视频**（交互稿 §03）Android 侧还没做：进画中画时采集照跑；不在画中画而切后台的场景要补
  `closeCamera` / 回前台恢复。
- 悬浮球拖到底部 = 挂断（交互稿 M2）没做；全屏来电 `fullScreenIntent`（差异 5）属推送阶段，MVP 不做。
- 「只引 Engine 自画 UI」的示范、日志回传汇入时间轴仍是 ⬜（见 CLIENT_PARITY）。

## 已知坑 / 限制

- **`IMGrid.dimensions` 的默认 aspect 是 0.7**（竖屏手机上头部与控制条之间那块区域的形状），
  不是 0.5：按 0.5 算 9 个人会排成 2×5，与 iOS 的 3×3 对不上。真机上用的是量出来的实际比例。
- **运行时权限只能从 Activity 请求**，拨出前 Kit 未必有界面在前台——所以有 `IMPermissionActivity`（透明、不入最近任务）。
  「问过没」记在 `im-rtc-kit` SharedPreferences 里：Android 没有「未决定」这个状态可查。
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
