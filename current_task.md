# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。

## 当前焦点

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

## 上一轮

**Pixel 上「carol 登录不了」查清了：不是 device_id，是地址与链路（2026-09-07）**。
`./scripts/test.sh` 六步全绿。**SDK 与 Demo 一行没错**——`android-Pixel-2-XL` 已在
服务端日志里坐实，`sanitizeDeviceId` 那条修复是好的。

真正的原因是两件叠在一起，而且都在代码之外：

| | |
|---|---|
| Demo 记着的地址是 `http://127.0.0.1:8787` | 上一轮真机验收就是**靠 `adb reverse tcp:8787 tcp:8787` 打的隧道**，所以那个地址当时是通的、于是被 `onLoggedIn` 记进了 prefs |
| 隧道没了 | `adb reverse` **不过夜**：拔线、重插、`adb kill-server`、手机重启，任意一件都会把它抹掉，而 prefs 里那行地址还在。于是每次启动都是 `Failed to connect to /127.0.0.1:8787` |

**这台 Pixel 填局域网 IP 也没用，但原因不是「AP 客户端隔离」**（第一版这么写，测细了才发现下错了）。
Pixel `192.168.1.11` **ping 得通路由器 `.1`，也 ping 得通 iOS 真机 `.10`**——不是隔离所有客户端。
坏掉的是 **Pixel ↔ Mac 这一对**，且两个方向都坏：Mac 上 `arp -n 192.168.1.11` 是 `incomplete`，
Pixel 上是 `Destination Host Unreachable`，**互相 ARP 不到**。
Pixel 无 VPN（`NOT_VPN`、无 tun 口），Mac 防火墙关着，两台还是**同一个 SSID 同一频段**
（`SLT-Fiber-2.4G_0878`）。对不上的是 BSSID：Pixel 挂 `b4:0f:3b:04:08:7d`，
Mac 的网关 ARP 是 `…:08:78`——**同一 SSID 下的两个 AP（主路由 + 扩展器）**，
这两个之间的客户端互访没打通。把 Pixel 的 WiFi 关了再开强制重关联**没用**，它还是回 `:7d`。
所以本机环境那节写的「真机必须填 Mac 的局域网 IP」对 PKD130 成立，**对这台 Pixel 不成立**，
它只能走 `adb reverse` + `127.0.0.1`。

**PKD130 一直没事，就是因为它走的是另一条路**：服务端日志里它的连接来自
`192.168.1.17` / `192.168.1.9`（`read tcp 192.168.1.12:8787->192.168.1.17:…`），
是真的局域网直连。那个地址不依赖任何隧道，拔线重启都还在，所以它记在 prefs 里永远有效。

### 顺手补的一行日志

`DialerScreen.onLogin` 原先是 `DemoSession.login(server, user) { errorLabel.text = it }`——
失败原因**只进那个 label**，而 label 在拨号页底部要滚动才看得见。症状是：
**手动登录失败在 logcat 里一片空白**（自动重登反而有 `IMRTCLog.i` 记录），
于是「连不上」与「人压根没点那一下」分不出来 —— 这次排查就卡在这儿。
现在同时 `IMRTCLog.w("demo", "手动登录失败：…")`。真机复验过：
拆掉隧道点登录，logcat 里如实出现 `手动登录失败：登录失败：Failed to connect to /127.0.0.1:8787`。

### 失败原因搬到身份卡上（同轮拍板并落地）

原先「登录失败」四个字在身份卡上，**原因**却在拨号页最底部、要滚屏才看得见。
四个字分不出是地址不通、服务端没起、还是账号不对，而这三种要查的地方完全不同。

关键是**存在哪**：原因记在 `DemoSession.lastLoginError`（Session 层）而不是页面里，
因为**自动重登也要能显示**——那条路的失败发生在 `onCreate`，页面还没建好，
回调塞不进任何 label。而它恰恰是最需要说话的一条：这次的故障就是「记住的地址后来失效」，
用户只看见一个不动的登录页。登录成功与主动退出都会清掉它。

页面上是新的 `loginErrorLabel`，夹在连接态那行与登录按钮之间；
**与底部的 `errorLabel` 分开**（那个管通话/房间的报错）。空的时候 `GONE`，不留空行。
表单校验（字段没填）走页面自己的 `formError`，不进 Session——它跟换票请求没关系。

真机复验（Pixel 2 XL）：拆掉隧道启动 → 身份卡上直接是
`登录失败：Failed to connect to /127.0.0.1:8787`，**没点任何按钮**；
补回隧道点登录 → 红字消失、绿点亮起、无残留空行。

### 回环地址连不上时，直接给出补救命令（`LoginHint`）

**「自动重登失败就把地址退回可编辑态」这个方案被否了。** 因为这台 Pixel
**没有**能填的局域网 IP（见上），而隧道断掉时 prefs 里那行 `127.0.0.1:8787`
**本身是对的**——补上隧道立刻能用。把它清掉或标成可编辑，等于提示「换一个地址」，
而根本无可换：人会去填局域网 IP → 失败 → 翻服务端日志 → 那边一条请求都没有，
正是最难查的那类。**该改的是话术，不是输入框。**

新增 `LoginHint.explain(server, error)`：**回环地址**且**根本没连上**时，
在原因后面附上 `adb reverse tcp:8787 tcp:8787`。两个条件缺一不可——
判据按**异常类型**（`ConnectException` / `SocketTimeoutException` / `UnknownHostException` /
`NoRouteToHostException`，含 `cause` 链）而不是 message 里的字样，因为
`DemoApi` 的 HTTP 错误是**裸 `IOException`**，401/500 落不进这几个子类。
提示错了比不提示更糟，会把人往错方向带一整轮：填局域网 IP 的 PKD130 绝不能看到这句。

8 条单测（`LoginHintTest`）。**先把 `isUnreachable` 那半个判据摘掉，看「401 不提隧道」那条红过**。
真机复验（PKD130，它没有隧道）：地址改回环 → 提示带命令完整显示（`maxLines` 提到 8，
4 行会截掉命令那行）；地址改 `192.168.1.99`（局域网、没人应）→ **只有原因，不提隧道**；
改回 `192.168.1.12` → 正常登录。

**没做**：`defaultServer` 的取值策略仍然没动，「失败的地址不记住」保持原样。
路由器那边两个 AP 互不通的问题也没碰——那要进路由器后台，命令行够不着。

## 更早

**SDK 层挡住不合规的 device_id + 握手错误按 retryable 分流（2026-09-07）**，
`./scripts/test.sh` 六步全绿。都是上一轮真机验收暴露出来的。

| 改动 | 为什么 |
|---|---|
| `IMCallEngine.Config` 构造即校验 `device_id` | 不拦的症状是：服务端回 1004、客户端无限重连、界面只写「登录失败」，而服务端那句说得很清楚的「出现了 `' '`」**到不了端上**。**只校验不改写**——device_id 要求跨重启稳定，SDK 悄悄改掉宿主给的值，宿主自己那套设备管理就对不上账了 |
| 握手失败按 `code.retryable` 分流 | 原先无条件 `closeAndReconnect`。**1102 token_expired 是唯一可重试的**（重连时可能已换到新票）；1004 / 1006 / 1101 / 1106 重连一万次参数还是那个参数 |
| 新增 `IMKickedOutReason.CONFIG_REJECTED` | `TAKEN_OVER`（回登录页）与 `AUTH_EXPIRED`（换票重来）都套不上「参数不合规」——换票救不了 device_id 里的空格 |

**「一次就放弃」不是「三次」**：4401 给三次是因为「票刚好过期」换张票就好，
而参数不会因为重连而改变。三条用例都先把 `retryable` 判反、看它红过。

**跨端契约缺口（重要）**：`CONFIG_REJECTED` 与「握手按 retryable 分流」是**五端共同的行为**，
目前**只有 Android 有**。iOS / Web / 桌面收到 1004 仍会无限重连——同样的 device_id 问题
在那三端上还是老样子。见 `../im-rtc-server/docs/CLIENT_PARITY.md` 的对应行。

## 更早

**日志回传真机验收 + 修掉一个只在带空格机型上炸的登录 bug（2026-09-07）**，
`./scripts/test.sh` 六步全绿。在 **Google Pixel 2 XL（Android 11）** 上验的。

### 验收发现的两件事

**① `device_id` 带空格 → 登录一律失败。** `deviceId` 是 `"android-${Build.MODEL}"`，
而 Pixel 2 XL 的 MODEL 就是 `Pixel 2 XL`——协议 §2.5 规定 charset 只有 `[A-Za-z0-9_-]`，
服务端一律回 1004，客户端无限退避重连，界面上只写着「登录失败」。

**这个 bug 之前没暴露，是因为验收用的 OPPO PKD130 型号里恰好没有空格。**
「Redmi Note 8 Pro」「MI 8 Lite」「moto g(7) power」都会中招。已加 `sanitizeDeviceId`
与 11 个真实机型名的用例。

**② 信令层不打帧日志 → 回传做好了也定位不了「无法挂断」。** 原先只有连接生命周期
（连接/握手/心跳/被踢）有日志，上下行帧一条都没有。于是「按了挂断却没挂掉」在日志里
是一段空白：分不出是 Engine 压根没发、发了服务端没收到、还是收到了应答没回来——
三种情况要查的地方完全不同。已在 `sendFrame` / `handleText` 各加一条 debug，
带 `req_id` 与 `call_id` / `room_id`。

### 真机上跑通的完整链路

```
15:57:26.147 server         发起通话 call_id=call-3f96… frame=call.invite
15:57:26.151 server         通话结束 reason=offline
15:57:26.497 android-carol  ↑ call.invite req=a-2
15:57:26.508 android-carol  ↓ call.invite.ok req=a-2 call_id=call-3f96…
15:57:26.510 android-carol  ↓ call.ended call_id=call-3f96…
```

**已知偏差**：客户端时间戳比服务端晚约 350ms（设备时钟），所以时间轴上
「服务端先发起、客户端后发 invite」看着是反的。timeline 用各端自己的时间戳是有意的
（用服务端收到的时刻就对不上端上的行为），排查时留意这个偏移。

## 更早

**日志回传服务端（2026-09-07）**，`./scripts/test.sh` 六步全绿（新增 9 条单测）。

此前 Android 只有 logcat，而 logcat 要人接着线、还要在出问题的那一刻正好开着。
「无法挂断」那类问题的现场因此拿不到：服务端只看得见「帧没来」，
**到底是 Engine 没发、发了没到、还是界面根本没调，从服务端一侧分不出来。**

| 落点 | 说明 |
|---|---|
| `demo/.../RemoteLogSink.kt`（新） | 攒批 1s 送到 `/v1/dev/logs`，落成 `client-android-<user>.log`，进 `timeline.py` 的同一条时间轴 |
| `DemoLogSink` 改成 fan-out | **与 logcat 并联而不是替换**。iOS 那边装了远程 sink 之后 Xcode 控制台就没有 Engine 日志了；Android 上 logcat 是现场排查的主力，不能因为多了个回传就关掉 |
| `demo` 模块加单测 | 手写的 JSON 序列化与攒批队列都是纯逻辑，跑 JVM 不需要设备 |
| `IMErrorCode` 补 1106 `app_disabled` | 多租户加这个码时漏了四个客户端仓，本仓的向量断言把它抓了出来 |

三个坑照 iOS 的教训避开了：**超时必须显式设短**（那边踩过默认 60 秒 + 发送闩，
一个卡住的请求就让后面所有日志静默丢掉）；队列满了**丢最旧**的；
发失败**不重试不回队**（重试只会在服务端不可达时把队列撑爆）。

**JSON 手写而不是用 `org.json`**：后者在 JVM 单测里是空壳桩，方法一律返回默认值，
测试会假绿；而 Engine 里那套 `IMJson` 是 `internal`，跨模块用不了。
转义那条用例先破坏实现看它红过。

**没做**：真机验收。只有 JVM 单测 + MockWebServer，没在真机上跑过一次完整的
「打电话 → 看服务端日志文件」。

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
- **libwebrtc 锁 M150**（`150.7871.01`），与 iOS 的 M152 对不齐是已知且可接受的；H.264 要专门跨端实测。

## 本机环境（2026-09-05 实测）

JDK 17（`/usr/libexec/java_home -v 17`）· SDK 到 android-36 / build-tools 36.0.0 · `adb` 在
`~/Library/Android/sdk/platform-tools/`（不在 PATH）· 两台真机：**OPPO PKD130 / Android 15**（局域网直连）与 **Google Pixel 2 XL / Android 11**（`903KPED2067148`，**只能走 `adb reverse`**）·
本机 Intel Mac（模拟器 x86_64，真机 arm64，两个 ABI 都要能出包）。

## 关联工程 / 常用命令

- **各端能力对照表：`../im-rtc-server/docs/CLIENT_PARITY.md`**（✅ 只写在那里，本文件不重复）。
- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。**真机的地址二选一**，见「已知坑」第一条：局域网 IP，或 `adb reverse tcp:8787 tcp:8787` 后填 `http://127.0.0.1:8787`。
- 常用命令：
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口：门禁 ×3 + 向量可达 + assembleDebug + 纯 JVM 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ```
