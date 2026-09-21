# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-19（快照整理时移出活快照）」是 09-18 的焦点原文；其上「2026-09-17 傍晚（/simplify 清理收口时移出活快照）」；再往前是「SDK 1.0.0 公网发布后精简：精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 · 发版 server `docs/ops/RELEASE.md` ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

- **09-20 已改、未 commit、未真机验：通话页全屏 + 后台久了回来「界面丢了通话还在」**（真机复现，宿主 im-android 20:46~21:31，进程 / 信令 / 媒体全活、服务端认为人还在房里，对端一直显示他在）。
  根因：`IMCallPresentation.apply` 只在形态变化那一次拉通话页，形态已是 fullscreen 就不再拉。修法：宿主页回前台且通话未结束就补拉（判据 `IMPresentRules`，`PresentRulesTest` 7 条）；
  前台服务通知补 `contentIntent`（原先写着「点按返回通话」却是死的）；`IMCallActivity` 补生命周期日志（tag `kit`，`通话页 onCreate/onStop/onDestroy isFinishing=…`）。
  已 `publishToMavenLocal`，im-android `settings.gradle.kts` 暂切 mavenLocal（**联调完要切回 JitPack**）。真机验法：通话中保持全屏 → Home → 久等（或开发者选项「不保留活动」）→ 回来应自动回到通话页；下拉通知点「通话中」也应回去。

- **09-19 新增 `IMCallEngine.fetchCallHistory(limit, cursor, onResult)`**（`IMCallHistory.kt`，`GET /v1/calls`，游标翻页，只返回本人，结果回主线程）：`CallHistoryTest` + `ActionResultTest` 两条新用例过、全量单测过；Demo 通话记录页改成调它（滚到底加载下一页，标题栏 ↻ 刷新），`DemoRecords.kt` 与本地拼记录已删。**未真机验**；依赖服务端 `requireBearer` 不再核对设备号（同日已修）。

**2026-09-19：三处修完，真机已验（PKD130 × Chrome，`delay` + `silence` 故障注入），已推送。**
- **判死重连关旧连接改用 1001**（`78ca689`）：心跳超时、网络变化探测判死都走 `closeAndReconnect`，一直带着 1000——协议里 1000 是 logout，服务端收到就结束会话、移出房间，随后的重连只能「恢复失败，开新会话」。
  **这两条路上的通话从 09-05 起就没恢复成功过。** 从服务端日志抓到；iOS / Web / 桌面一直是 1001（Web 实为不带码关，浏览器不许发 1001，见 `b255505`）。
- **发布没等到应答不再判死**（`62de750`，对齐 iOS）：2003 / 2004 / 2007 通话与会议房都发 `publish_deferred`，服务端真拒仍按 R4 收场。「重连恢复不该重复发布」那条老测试原先断言的正是这个 bug，改成先落 `.ok` 再断线。
- **远端轨道归属表整表替换**（`4d01377`）：原先 `putAll` 只加不删，同一个人重推换了 `track_id` 后旧 id 仍指着他，新旧两条轨道挂到同一个渲染器上。
  **很可能就是 09-18「`bot02` 的格子里放的是 carol 的画面」那条悬着没定位的问题**（同为渲染器绑错人），但没复现确认，待下一轮翻页时验。
- **回前台立即重连补上 2 s 最小间隔**（`13a309a`）：与网络变化共用一道闸；原先连着切前后台每次回前台都当场开一条新 socket。
- **网络变化立即重连**（09-18 晚，`d27ef60`）：`IMCallKit.start()` 监听默认网络，换网调 `IMCallEngine.notifyNetworkChanged()`；等着重连的立刻连、退避归零，连着的探 3 s 判死立刻重连（`IMReconnectTimer` / `IMNetworkProbe`）。
  真机验法：通话中关 Wi-Fi 再开，logcat 看 `系统网络变了` → `规则=网络变化立即重连`。状态见 CLIENT_PARITY `[^netchange]` `[^pubdefer]`。

**已收口（细节在 archive「2026-09-19」节）**：会议房 M2 真机修复（协议版本 1 → `PROTOCOL_VERSION`、退订再重订画面定格、底部条 / 紧凑名字牌 / 末页排布 / 标题栏写房号），
以及 `IMDownlinkStats`（下行解码实况）与 `IMVideoFitter` 画面缩放判据两条诊断日志（同名字段与 iOS 对齐）。真机验过：底部条四格对称、名字可读、新标题栏。**没验**：翻走 >10 秒再翻回、25 人、断网恢复。

## 下一步

0. **还没验的**：视频通话拨出中收起、接通后球变视频缩略没点过；1v1 视频默认走听筒（改前就这样，要不要默认扬声器待定）；翻页后格子里是不是同一个人（见上，`4d01377` 是否已修）。
1. **真机窗口清单**：
   - 铃声：蓝牙耳机场景 + **补记机型与 Android 版本**（O+ / O- 焦点 API 走的哪条）。
   - 老批次（forceEnd 断网 / 秒挂、后台重连节奏、1v1 视频细节）：archive「2026-09-15：forceEnd …真机验收清单」。
2. 2.0.0 调用结果改造：真机验 → 用户通知后发版。
3. 待办：静默失败清单 `../im-rtc-server/docs/ops/silent-failure/android.md`。

## 已知坑 / 限制
- **`frontCamera` 标志每通新采集要重设**（09-21，对齐 iOS 后置挂断问题）：Android 新采集总是选前置，画面一直是对的，但标志只在翻转回调里改，翻后置挂断后下一通本地预览少一次镜像；现按所选设备设置。

**发布**
- 下次发版：改 `IMRTC_VERSION` 与 `IMCallEngineVersion.VERSION`（不等时 `SdkVersionTest` 红）→ 推 tag（与版本同号、不带 v）→ 请求一次 pom 触发构建。**JitPack 会缓存失败**：失败要在 JitPack 页面删记录再试。
- group 必须是 `com.github.BLiYing.im-rtc-android`（`gradle.properties` 的 `IMRTC_GROUP`），换回别的 uikit 对 engine 的传递依赖会拉不到。

**测试 / 工具**
- `SignalConnectionTest` 里 `scheduler.advance(N)` 超过 30 s 会触发心跳超时重连、污染退避断言：压在 30 s 内，或显式 `transport.deliver(PONG, "")`。
- 仓根 `temp_verify.py` 是多会话共用的活文件，本仓验证脚本单独建文件。
- 禁止 `org.json`（JVM 单测里是空壳桩）；`protocol/` 与 `statemachine/` 不许 import android.*（门禁守着）。

**真机 / 环境**
- **真机连不上服务端先看链路**：局域网 IP（PKD130 可用），或 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`（**Pixel 2 XL 只能走这条**）。拔线 / 重启后隧道就没了，症状像登录 bug。
- PKD130（ColorOS / Android 15）`pm revoke` 被挡：测无摄像头权限去系统设置手动关。
- 日志回传只有 `-demo-login` 接收口，生产环境空转。
- 七项界面按「默认已验」收口（09-08 拍板）；其中「返回键收小窗」已被证伪（收小窗后远端视频回不来）。

**媒体 / 渲染**
- **iOS 发的永远是单层 H264**，SFU 降层只砸 Android——排查「为什么只有 Android 糊」先想这条。
- `maxBitrateBps` 是 h 一层的数、不是上行总量；已由 `simulcastUplinkBudgetBps` + `PeerConnection.setBitrate` 播种兜住。
- libwebrtc 锁 M150（`150.7871.01`），与 iOS M152 不齐可接受；H.264 要专门跨端实测。
- **2006 阈值「3」未校准、Kit 不接 2006**：见 server「已知坑」。
- **摄像头意图必须在进房前给 Engine**（`IMCallKit.syncCameraIntent`）；接通后才开的靠 `publishCameraIfMissing` 补发。
- **关摄像头停的是采集**：进房前关 = `stopLocalPreview`，通话中关 = `setMuted` → `setCapturePaused`。
- 本端 track id 必须就是 cid，**预览与发布是同一条轨道、同一个 cid**（`IMLocalVideoCid`）；本端渲染器与轨道谁先到都可能，统一在 `IMWebRTCAdapter.bindLocalView` 对齐；远端轨道按 track_id 认领；到达顺序不定，统一在 `bindRemoteTracks` 判重换绑。
- 「人先进来、轨道后到」是常态：摆格子的动作要能在轨道到达时再做一遍（`invalidateReportedLayer`）。
- `SurfaceViewRenderer` 的 init / scaler 只能主线程调，而调度器会吞异常（症状只有全黑）→ 一律走 `IMWebRTCAdapter.onMain`。
- `SurfaceView` 息屏就没、不重画上一帧：回前台某格纯黑 = 对端不发帧了，别查渲染器。
- 渲染器按 uid 整通复用（`IMCallKit.remoteViews`）；小窗叠全屏要 `setZOrderMediaOverlay(true)`；离房停媒体的判据是房间与通话都回 idle。

**UIKit / 平台**
- `IMGrid.dimensions` 默认 aspect 0.7；`GridLayout.spec` 不能带权重，往小改行列数前先退回 `UNDEFINED` 或 `removeAllViews()`。
- 横排占位格高度写死 0；小窗吸角要等容器量出来。
- 运行时权限只能从 Activity 请求 → `IMPermissionActivity`；前后台只认亲眼看见 started 过的界面（`IMForegroundState`）；通话中要 Context 用 `appContext`。
- 前台服务：通话中必须起；类型不能降级、不能超出已授权权限（Android 14+ 会循环崩），`start()` 先判 `IMForegroundTypes.granted`。
- 悬浮球默认应用内浮层、不申请 `SYSTEM_ALERT_WINDOW`。
- `onDisconnected(code, willReconnect)`：`willReconnect` 由 `IMSignalConnection` 当场裁决，Kit 直接用 `!willReconnect` 判「已放弃」。
  **关闭码要不要重连只看 `IMCloseCode.shouldReconnect`**（与 `error_codes.json` 的 `close_codes[].reconnect` 一致），特例只有两条：4401 数到 3 次、1000 非自主关闭照常重连。

- **本端主动关旧连接（判死重连 / 换网探测）用 1001，不能用 1000**：1000 是 logout，服务端会当场结束会话，随后的重连恢复不了（09-19 修，`closeAndReconnect`）。

**体量（都贴着 600）**：`IMCallView.kt` 581、`IMCallKit.kt` 597、`IMSignalConnection.kt` 575、`IMCallViewState.kt` 593、`IMWebRTCAdapter.kt` 572——下次改先拆。
09-18 已拆出三个：`IMRemoteVideoBinding.kt`（远端画面挂载/换绑）、`IMCallViewGrid.kt`（格子那一半）、`IMCallViewBanner.kt`（顶部橙条）。

## 关联工程 / 常用命令

本机：JDK 17（`/usr/libexec/java_home -v 17`）· SDK android-36 / build-tools 36.0.0 · `adb` 在 `~/Library/Android/sdk/platform-tools/`（不在 PATH）· Intel Mac。
真机：**OPPO PKD130 / Android 15**（局域网直连）· **Google Pixel 2 XL / Android 11**（`903KPED2067148`，只能 `adb reverse`）。

- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口（6 步）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  RTC_CONFORMANCE_DIR=../im-rtc-server/docs/conformance ./gradlew :call-engine:testDebugUnitTest
  ./gradlew publishToMavenLocal && ./gradlew -PimrtcSdk=local :demo:installDebug   # Demo 用本地包
  ./gradlew -PimrtcSdk=public :demo:installDebug                                    # Demo 用 JitPack 包
  ```
