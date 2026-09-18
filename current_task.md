# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-17 傍晚（/simplify 清理收口时移出活快照）」；再往前是「SDK 1.0.0 公网发布后精简：精简前全文」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 · 发版 server `docs/ops/RELEASE.md` ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

## 当前焦点

**2026-09-18 晚：网络变化立即重连（未上真机）**。20:45 alice 的 Wi-Fi 自己重连换了 IP，信令在 30 秒退避档空等、
错过服务端 30 秒恢复窗口。`IMCallKit.start()` 监听默认网络，换网就调 `IMCallEngine.notifyNetworkChanged()`：
等着重连的立刻连、退避归零；连着的探 3 s，判死立刻重连；两次至少隔 2 s。真机验法：通话中关 Wi-Fi 再开，
logcat 看 `系统网络变了` → `规则=网络变化立即重连`。状态见 CLIENT_PARITY `[^netchange]`。

**2026-09-18：会议房 M2 真机验收进行中（OPPO PKD130 / Android 15）。M2 的 Engine 与 UIKit 两段已在 09-18 凌晨做完（`e55bbbc` / `493883e`，见 server `docs/design/MEETING_ROOM_DESIGN.md` §7 第 2、5 步）。今天全是真机才暴露的修复，`test.sh` 6 步全绿。**

- **补了两条看不见的东西**（未提交，为 09-18 下午那次联调加的）：**下行一个统计都没有**——
  `IMDownlinkStats` 每 5 s 采 `inbound-rtp`，只在「分辨率 / 解码器 / 在不在出帧」变了时打一行
  `下行解码实况 uid= 分辨率 解码帧=+n 收包=+n 丢包=+n 码率= 解码器=`。有了它才分得开
  「包没来 / 来了拼不出帧 / 拼全了解不出来」，房间 41642481 那次 frank 全程黑就是卡在这儿。
  另加 `IMVideoFitter` 的 `画面缩放判据 owner= view= video= fraction= mode=`（与 iOS 同名字段），
  FILL/FIT 判错时原先一条错都不报。
- **握手报的协议版本一直是 1**（`e8f0110`）：两处真相源（`IMEnvelope` 与帧声明的默认值）不一致，
  真机连 2.0.0 服务端当场被拒，症状是「接入参数被拒」。统一到 `IMEnvelope.PROTOCOL_VERSION`，
  `SdkVersionTest` 改成断言 **`transport.sent` 里真的发出去的那一帧**。
- **退订再重订之后画面定格**（`861dfbc`）：M2 第一次让「退订→重订」成为常规动作，
  协议 `track_id` 不变但媒体层拿到的是**新的轨道对象**，而 `bindRemoteTracks` 只比渲染器不比轨道，
  判成「没变」直接跳过 → 新轨道从没 `addSink`，画面停在旧轨道的最后一帧。三端同病。
  顺带把远端画面的挂载/换绑拆进 `IMRemoteVideoBinding.kt`（适配器贴着 600 行）。
- **演讲者底部条第一格与最后一格被切**（`0e1b791`）：4 格 × 84dp + 间距 + 边距 = 392dp，
  而常见手机只有 360dp，`CENTER` 溢出就从两头各切一截。改成 `IMGrid.stripSide` 取小（这台机器 76dp），
  像素量过：四格都是 152×152、左右边距各 32px。
- **小格子里名字放不下**（`b134bcd`）：固定件吃掉 54dp，76dp 的格子只剩 22dp，`carol` 都显示成「ca…」。
  边长 < `IMGrid.COMPACT_TILE_DP`（110，三端同值）自动换**紧凑档**，固定件压到 28dp。
- **末页不满要从左上排起 + 空白处也能翻页**（`bef9ef3`）。
- **标题栏改成写房号、点一下复制**（`8c1acff`）：人数只留右上角「👥 N」，标题不再重复同一个数字。
  拆出 `IMCallViewBanner.kt`（`IMCallView` 加完这几行顶到 604）。
- **诊断**（`5f83078`）：远端画面绑定 / 解绑时报 `track_id + uid + 渲染器 hash`。

**悬着没定位**：翻页把 carol 提到第一页之后，**标着 `bot02` 的格子里放的是 carol 的画面**，
carol 自己那格是黑的（11:24 截图）。假客户端发合成 RTP、永远解不出画面，所以只能是渲染器绑错人。
诊断日志已装机，**还没复现抓到**。

**真机验过**：底部条四格对称、底部条名字全可读、新标题栏。
**没验**：翻走 >10 秒再翻回（那次被钉住状态吃掉了手势）、25 人、断网恢复。

## 下一步

0. **还没验的**：视频通话拨出中收起、接通后球变视频缩略没点过；1v1 视频默认走听筒（改前就这样，要不要默认扬声器待定）。
1. **真机窗口清单**：
   - 铃声：蓝牙耳机场景 + **补记机型与 Android 版本**（O+ / O- 焦点 API 走的哪条）。
   - 老批次（forceEnd 断网 / 秒挂、后台重连节奏、1v1 视频细节）：archive「2026-09-15：forceEnd …真机验收清单」。
2. 2.0.0 调用结果改造：真机验（见当前焦点）→ 用户通知后发版。
3. 待办：静默失败清单 `../im-rtc-server/docs/ops/silent-failure/android.md`。

## 已知坑 / 限制

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
