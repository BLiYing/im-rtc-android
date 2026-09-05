# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log`。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；界面以草图 `docs/design/sketches/RTC_CALL_UX_SKETCH.html` §02~§05 为准。

## 当前焦点

**任务一到任务五全部落地（2026-09-05）：骨架 → 协议层 → 状态机 → 信令与门面 →
媒体 → UIKit 与 Demo。`./scripts/test.sh` 六步全绿（57 个用例，纯 JVM），
并且已经在真机 OPPO PKD130 上装起来跑通了「免密登录 → WS 握手 → 建会议房 → 进房 →
媒体拉起 → 通话界面计时」。**

**真机上没验到的只有一件事：真的出声出画。** 原因是环境不是代码——
路由器做了 AP 隔离（手机 192.168.1.9 与 Mac 192.168.1.12 同网段但互相 ping 不通），
只能用 `adb reverse` 把信令走 USB 隧道，**UDP 媒体过不去**；而且当时服务端带的是
`-ice-loopback`。换一个能互通的网络就能接着验。

| 落地物 | 内容 | 怎么验的 |
|---|---|---|
| Gradle 骨架 | 四模块 + 版本目录 + wrapper 8.13（AGP 8.12.1 / Kotlin 1.9.24 / JDK 17） | `assembleDebug` 出 791 KB 的 Demo APK |
| `protocol/IMJson` | 严格值模型：**类型里没有 Null 与 Double 两个 case** | — |
| `protocol/IMJsonParser` | 严格解析：拒 null / 拒浮点 / 整数**按值**判定 / 越界拒 / NUL 拒 / 重复键 last-wins | 11 个用例 |
| `IMJsonError.Kind` | STRUCTURE → `bad_envelope`，VALUE → `bad_params`（`envelope.json` 分开断言的那两个） | 同上 |
| 向量加载器 | 去 `../im-rtc-server/docs/conformance` 读五份，**找不到就失败，不静默跳过** | 5 个用例 |
| 四道门禁 | 体量 / 分层 / 日志纪律 / 向量可达，**每道都带自检** | `test.sh` 第 1~4 步 |
| `protocol/` 信封与帧 | 信封、编码硬规则（同构数组 / 嵌套两层）、40 个帧的字段声明与注册表、默认值填充 | `envelope.json` 26+9 条 |
| `protocol/` 枚举表 | 45 个错误码（含那句英文 msg）、6 个关闭码、12 个 reason、群主导优先级、时长算法 | `error_codes.json` + `reasons.json` 全表 |
| `statemachine/` 通话机 | §5.1，含「没有 ended 状态」「便利回调只 1v1」「idle 下迟到帧静默丢弃」 | `call_fsm.json` 16 例 73 步 |
| `statemachine/` 房间机 | §5.3 的 R1~R3：本地拒绝 / 中间态缓存重放 / 订阅换层幂等 | `room_fsm.json` 8 例 41 步 |
| `statemachine/` 总状态 | 连接级事件、重连恢复失败合成 onCallEnd、通话结束把房间归零 | 同上 |
| `signaling/` | OkHttp WS、握手、心跳、req_id 配对、退避重连、4401 三次上限 | 11 条假连接时序用例 |
| `IMCallEngine` 门面 | 24 条回调的监听器、核心循环、请求被拒退回 idle、媒体起停 | 6 条假传输 + 假媒体用例 |
| `call-engine-webrtc` | 两条 PC、候选缓冲、**协商串行化**、simulcast 三层、前台服务、音频路由 | **真机跑通到 ICE CONNECTED** |
| `call-uikit` | 通话页（三段式）、控制条、九宫格算术、视图模型 reducer | 6 条纯 JVM 用例 + 真机截图 |
| `demo` | 拨号 / 通话记录 / 设置 三屏 + 选人多选 + `JavaApiCheck.java` | 真机装机跑通 |
| `IMVideoProfile` | 画质档位（360p/720p/1080p + simulcast 三层折算），**与 Web / iOS 同一张表** | 4 个用例 |

**加载器用的就是本仓自己的解析器**——五份向量文件本身就是第一批测试输入（实测：
五份里没有 null、没有浮点、没有越界整数，严格解析器能原样吃下去）。

两条已拍板的决定（五仓文档已同步，2026-09-05）：
1. **Android 进入产品范围**，四仓变五仓。设计文档 §1 #6、§8、§10（新增 P6）、§11（新增第 10 项）已改。
2. **Kotlin 独立实现，不共享桌面端的 C++ 核心。** 理由：Android 的 libwebrtc 绑定本就是 Java，
   走 C++ 核心仍要写一整层 Java 媒体适配器，能共享的只有协议 + 状态机 + 信令约 3k 行，
   代价却是 NDK + 四个 ABI + JNI 生命周期。与设计文档否掉 Rust/KMP 是同一条理由。

**开工闸门只剩一条，且只挡后半程**：
- ~~iOS 媒体真机验收~~ —— **iOS 已在真机测试中（2026-09-05）**，不再是阻塞项。
- **设计文档 §7.5 回调表冻结**——iOS 落地这一周里它还在加 `updateToken`、`setSpeakerOn`、
  `onConnected`，每一条都是「写第三个实现时才发现前两个漏了」。补表成本现在是 ×5。
  **但这条只挡任务三（门面与回调表）往后**：任务一（Gradle 骨架 + 向量 runner）与
  任务二（协议层 + 状态机）吃的是协议与向量、不是回调表，**已经做完了**。

**开工前本仓只做一件事：接收契约。** 从 Kotlin / Java 视角评审
`../im-rtc-server/docs/RTC_PROTOCOL.md` 与 `docs/conformance/*.json`，
发现「Kotlin 侧别扭 / Java 宿主调不了」的地方**现在就回 server 仓提**。

## 下一步

0. ~~五仓文档同步 + 建 `CLIENT_PARITY.md`~~ —— **已完成（2026-09-05）**。
1. ~~任务一：Gradle 骨架 + 向量 runner~~ —— **已完成（2026-09-05）**。
2. ~~任务二：协议层 + 两个状态机~~ —— **已完成（2026-09-05）**，五份向量逐条跑过。
3. ~~任务三：信令 + 门面~~ · ~~任务四：媒体~~ · ~~任务五：UIKit + Demo~~ —— **已完成（2026-09-05）**。
4. ~~Demo UI 对齐 iOS + 群呼选人~~ —— **已完成（2026-09-05）**：拨号页改成 iOS 那四张卡
   （身份 / 单人 / 多人 / 会议房间）+ 底部 tab，加了**九人名单的多选选人**（上限 8，自己 +8 = 9 格），
   记录页改成带图标与「呼出 · 结果 · 时刻」的列表（未接来电红字），设置页加了画质档位与关于。
5. **接下来只剩「验」，不剩「写」**（按优先级）：
   1. **换一个手机与 Mac 能互通的网络**（关掉 AP 隔离，或用 Mac 开热点给手机连），
      服务端**去掉 `-ice-loopback`**，然后：Android ↔ Web 互打一次、Android ↔ iOS 互打一次。
      这是「能不能打电话」的唯一判据。
   2. 真机验静音互见、翻转摄像头、切后台、息屏、蓝牙耳机切换。
   3. H.264 跨端实测（清单见 `../im-rtc-server/docs/CLIENT_PARITY.md` §3）。
6. **UIKit 还欠的几件**（都不挡通话，属打磨）：悬浮球 / 小窗、来电横幅（现在是全屏页）、
   双击放大某一格、本端预览挂进格子、「只引 Engine 自画 UI」的示范、客户端日志回传服务端。

**Demo 是本仓自己的一个 Gradle 模块（`demo/`），不是另建工程**——不需要你手动新建 Android 项目，
`settings.gradle.kts` 与四个模块都由任务一一次生成。iOS 那边 Demo 是独立 Xcode 工程，
是因为 SPM 包和 App 工程在 Xcode 里天生两张皮；Gradle 没这个问题，一个构建里挂四个模块就行。

## 已知坑 / 限制

**三个开工前问题已定（2026-09-05）**
- **libwebrtc 里程碑对不齐，接受**：iOS 是 M152（`stasel/WebRTC` 152.0.0），Android 侧
  `io.github.webrtc-sdk:android` **没有 M152**，最新是 M150（`150.7871.01`），另有仍在打补丁的
  M144 稳定线。**锁 M150，兜底 M144**（改版本目录一行的事）。防线不是版本对齐，而是
  协议 + 向量 + 跨端互打，见 `../im-rtc-server/docs/CLIENT_PARITY.md` §3。**H.264 要专门跨端实测。**
- **UI 用原生 View，不用 Compose**：`SurfaceViewRenderer` 本就是 View；UIKit 是要塞进别人 App 的库，
  不该把 Compose 运行时强加给宿主。宿主自己是 Compose 应用不受影响（`AndroidView` 能嵌）。
- **Demo App 归本仓，不用你另建工程**；「宿主」是另一件事（别人的 App 来接我们的 SDK），
  公司目前没有 Android 宿主。**公开面的 Java 友好由 `demo/` 里的 `JavaApiCheck.java` 守**
  （纯 Java 调一遍全部公开 API，**编译即验证**）——这一招在 iOS 侧抓到过真问题。
  等真有 Android App 要接入时，再按 P5 的方式补一次接入示例。

**从另外三端搬过来的坑（别再踩第二遍）**
- **协议里三处与旧草案不同**：下行 `timeout` → `call.no_answer`；草图 §09 的 `room_ready` →
  `call.connected`；**Engine 状态机没有 `ended` 状态**（ended 是事件，草图里停 1.5s 的方框是 Kit 的展示状态）。
- **发送侧的默认值陷阱**（协议 §2.4）：「省略即取默认值」只对**真的省略**成立。
  显式写 `autoSubscribe = false` 会把默认的 `true` 覆盖掉，两种写法都会让人进了房收不到流。
  发送侧一律从帧字段声明起手再改字段。
- **4401 必须有重试上限**（三端同一个数：**3**）：重连带的是同一枚 token，没有上限
  就是拿同一把坏钥匙永远敲同一扇门。Web 端实测重试到第 19 次还在敲。到顶抛 `onKickedOut`。
  **放弃必须用闩**，只取消定时器会被排在后面的失败回调重新排回来。
- **便利回调只在 1v1 抛**（`onCallCancelled/Rejected/Busy/NoAnswer`）；群通话只抛 `onUser*`，
  否则违反「便利回调之后必定跟 onCallEnd」。
- **通话结束后房间必须回 idle**，否则之后每一帧都发向一个已销毁的房间。
- **层上界要随订阅一起给到服务端**，否则房间记 m、实际发 h。
- **早到的 ICE 候选要缓冲**：远端描述还没设就来的候选丢掉的话，媒体会间歇性不通；
  进房即订阅时协商发生得早，SDP 里可能一个候选都没有，三方会议必现。
- **重连之后要把握手结果接出去**：`resumed=false` 时房间要归零、`resumed=true` 时要重放攒下的意图。
  Web 端漏了这条，症状是「其实重连成功了，界面一直停在重连中」。
- **v1 不做主叫侧多设备扇出**：主叫的其他设备收不到「你的账号正在别处呼出」。写进 `CLIENT_PARITY`。
- **MVP 不覆盖锁屏来电**：Android 侧需要 FCM 高优先级推送 + Telecom/`ConnectionService`，属后续期。

**装机（2026-09-05 实测）**
- **`adb install` 会卡住不动，直到手机亮屏解锁**：OPPO / ColorOS 装 USB 传来的包要在机器上
  弹框确认（还要先在开发者选项里打开「USB 安装」）。屏幕黑着的时候命令**既不报错也不返回**，
  看起来像 adb 坏了——实测那次 `mWakefulness=Asleep`，卡了三分钟。
  **先解锁手机再装**，或者用 Android Studio 的 Run 按钮（它会提示）。

**九宫格能测之后才露出来的（2026-09-05）**
- **群通话 / 会议的标题不能显示某一个人的名字**：Kit 原来一律显示 `peer`，而群呼时 `peer`
  是名单里排第一的那个人。八个人的群呼，标题写着「alice」——那跟这通电话是谁、都有谁在
  一点关系都没有。现在按 iOS 的规则走人数：`会议（N 人）` / `群通话（N 人）` / 1v1 才显示对方 ID。
  **这个 bug 一直都在，只是选人做出来之前没人能拨出一通九人的电话**。
- **选人名单必须是 9 个人**：自己会被过滤掉，8 个名字只剩 7 个可选，最多凑出 8 格，
  **永远看不到真正的 3×3**——而九宫格正是这一屏存在的理由。

**真机上才露出来的（2026-09-05，OPPO PKD130 / Android 15）**
- **Android 9 起默认禁止明文 HTTP/WS**。本地联调的 `http://…:8787` 与 `ws://…` 全是明文，
  不开 `usesCleartextTraffic` 的症状是**「点登录没反应」，而且服务端日志里一条记录都没有**——
  请求在系统层就被掐了，很容易误判成服务端没起。这与 iOS 的 NSAppTransportSecurity 是同一个坑。
  **只开在 Demo/调试构建里**，生产走 wss://。
- **系统默认主题的 ActionBar 是盖在内容上的**：顶部的状态行与三个 tab 按钮整块被遮住，
  截图上看像「那几个控件没画出来」，实际布局里一直都在（`uiautomator dump` 能看到）。
  两个 Activity 都要 `NoActionBar` 主题。
- **协商必须串行**：发布 audio 与 video 两条轨道会产生两次 `publish.ok` → 两个 offer，
  第二条 answer 回来时 PC 已经 stable，native 层直接报
  `Failed to set remote answer sdp: Called in wrong state: stable`，那条轨道再也协商不上。
  现在 offer 排队、answer 落地后再补，并且 stable 状态下收到的 answer 直接丢弃。
- **媒体的启动判据不能只看 call**：会议房是直接 `joinRoom` 的，压根不经过 call，
  只按 `room_token` 判会一次都不触发，症状是「还没 start 就 publish，忽略」——
  人进了房但谁也听不见谁。
- **会议房是两步不是一步**：`POST /v1/rooms` 只回 `room_id`，票要再走
  `POST /v1/rooms/{id}/tokens`，而且**`device_id` 必须与 Engine 握手时用的那个一模一样**
  （房票绑定 room+uid+device）。对不上就是 `1101 token_invalid`。
- **路由器可能做了 AP 隔离**：手机与 Mac 同一个网段也互相 ping 不通。
  应急走 `adb reverse tcp:8787 tcp:8787`（手机的 127.0.0.1 隧道到 Mac），
  **但那只救得了信令，UDP 媒体过不去**——要真打电话得换网络。

**任务二踩到 / 定下的**
- **向量的比对方式是「递归子集」，不是全等**：对象只比向量列出来的键，数组先比长度再逐个
  递归，标量相等。这与服务端 Go runner 的 `matchSubset` 一比一对应，**五端必须一致**，
  否则「五仓跑同一份」是句空话。用全等比会把「回调按 §7.5 裁剪」误判成错误——
  `room.active_speakers` 帧里带 `participant_id`，而 `onActiveSpeakers` 只给宿主
  `[{uid, volume}]`。**第一版就是用全等比的，当场被这条抓住。**
- **`room_fsm.json` 要用 engine 总状态机驱动，不是房间机**：向量里有 `onDisconnected` /
  `onConnected` / `onKickedOut` / `onCallEnd`，这些只有把通话机与房间机合起来才说得清。
- **`call_fsm.json` 有的用例带 `context` 预置**（从半途开始，例如群通话中途加邀一上来就是
  connected 且已有 call_id）。忽略它的话第一步就会发出 `call_id=""` 的帧。
- **publish 的 `idle` 与 subscribe 的 `none` 用「不在表里」表达**，不是枚举值——
  省掉「表里有个 idle 条目」与「表里没有」两种等价写法。

**任务一踩到 / 定下的**
- **向量里 `input_data` 是原始 JSON 文本，`expect_data` 是对象**——两边形状不对称。
  第一版把两个都断言成对象，被用例当场抓住。任务二接「默认值填充」时别搞反：
  输入要按**文本**原样喂进解析器，期望值才是解析后的对象。
- **本机 Homebrew 装的 `gradle` 是坏的**：它的 `JAVA_HOME` 被写成占位符 `@@HOMEBREW_JAVA@@`，
  直接敲 `gradle` 会报 "JAVA_HOME is set to an invalid directory"。
  `scripts/test.sh` 里已兜底（`/usr/libexec/java_home -v 17`）——**用 `./gradlew` 或 `./scripts/test.sh`，别直接敲 `gradle`**。
- **wrapper 锁 8.13，别随手升**：AGP 8.12.1 要求 Gradle ≥ 8.13，而 8.13 本机缓存里正好有，不用下载。
- **`local.properties` 不入库**（里面是 `sdk.dir`，每台机器不一样）。新 clone 要自己建一份，
  或者用 Android Studio 打开一次让它生成。
- **`internal` 对同模块的单测是可见的**（Kotlin 的 internal 是模块级，AGP 给单测配了 friend path）。
  所以 protocol 层整个是 `internal`、公开面保持干净的同时，测试照样能测——
  **别为了「方便测试」把东西改成 public**。

**Android 侧特有的（详见 [CONVENTIONS.md](CONVENTIONS.md) §8）**
- 通话中必须起前台服务（Android 14 起还要 `FOREGROUND_SERVICE_MICROPHONE` / `_CAMERA`）。
- 通知要 `POST_NOTIFICATIONS`（Android 13+），没有它用户看不见「通话中」。
- 音频路由分两代 API（API 31 前后），两条路都要写；不设 `MODE_IN_COMMUNICATION` 就没有回声消除。
- 悬浮球默认走应用内浮层，**不默认申请 `SYSTEM_ALERT_WINDOW`**（敏感权限，影响宿主上架）。
- `PeerConnection.Observer` 跑在 signaling 线程上，禁止阻塞、禁止直接碰 UI。
- **禁止 `org.json`**：它在 JVM 单测里是空壳桩，一律返回默认值，测试会假绿。
  **`demo/` 是例外**（`DemoApi` / `DemoSession`）：那是宿主代码，跑在真设备上，没有空壳桩问题。
- 厂商 ROM 的后台限制差异很大，**「我这台过了」不等于「Android 过了」**。

## 本机环境（2026-09-05 实测，开工不缺东西）

| 项 | 状态 |
|---|---|
| JDK | 17.0.16（Homebrew），`/usr/libexec/java_home -v 17` |
| Android Studio | 已装（正式版 + Preview 各一份） |
| SDK | `~/Library/Android/sdk`，platforms 到 **android-36**、build-tools 到 36.0.0 |
| `adb` | `~/Library/Android/sdk/platform-tools/adb`（**不在 PATH，要么加 PATH 要么写全路径**） |
| **真机** | **OPPO PKD130 / Android 15（API 35）/ arm64-v8a，已连着** |
| 本机 CPU | x86_64（Intel Mac）——模拟器用 x86_64 镜像；**真机是 arm64-v8a**，两个 ABI 都要能出包 |

**首台验收机正好是 OPPO（ColorOS）**：厂商后台限制最严的那一类，前台服务与保活要在它上面过一遍
才算数——这比在 Pixel 上过更有说服力。但**别只测它**，交付说明里要写清楚测的是哪台。

## 关联工程 / 常用命令

- **各端能力对照表：`../im-rtc-server/docs/CLIENT_PARITY.md`**（逐端逐特性状态的**单一真相源**，✅ 只写在那里，本文件不重复）。

- 五仓（本地同级 `/Users/liying/IOSProject/im-rtc/`）：
  [im-rtc-server](https://github.com/BLiYing/im-rtc-server)（**协议契约在这里，只读引用**）·
  [im-rtc-ios](https://github.com/BLiYing/im-rtc-ios)（**本仓的对照实现**）·
  [im-rtc-web](https://github.com/BLiYing/im-rtc-web) ·
  [im-rtc-desktop](https://github.com/BLiYing/im-rtc-desktop) ·
  **im-rtc-android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（控制面 :8787，媒体面 UDP 7881）。
  启动时会打印局域网 IP——**真机必须填那个 IP**，`127.0.0.1` 在手机上指的是手机自己。
  `./scripts/e2e.sh media` 可以先确认服务端这边是通的，再来排查本仓。
- 常用命令（脚本随任务一落地）：
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次，装 pre-commit 体量门禁
  ./scripts/test.sh                # 唯一测试入口：体量门禁 + 编译 + 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/check-file-size.sh --selftest   # 门禁自检
  ```
