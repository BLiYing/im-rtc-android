# current_task 归档（只读）

> `current_task.md` 是**活快照**，退休的细节挪到这里，别再挪回去。
> 更完整的历史在 `git log`。

## 2026-09-06 上午 · 视频通路在真机上跑通（从「当前焦点」退休）

**上一轮：Android 的视频通路已在真机（OPPO PKD130）上跑通并逐条验过**（2026-09-06 上午）：
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
| 系统画中画那颗叉 | 删不掉（系统画的），定成**「收起」不是「挂断」**：通话继续，回宿主界面变悬浮球 |


## 2026-09-05 · 离房之后仍在发 `room.ice_candidate`（已修）

从 `current_task.md`「下一步」第 7 条退休下来的原文——**当时的判断只对了一半**，
留在这里是为了记住哪一步推错了：

> **一个复核时发现、还没修的真 bug**：**离房之后客户端仍在发 `room.ice_candidate`**，
> 服务端每 5 分钟回两条 `1203 not_in_room`（日志里 20:39:45 / 20:44:45 / 20:49:45… 一路到 21:24）。
> `driveMedia` 在 room JOINED→IDLE 时确实调了 `adapter.stop()` → `peers.stop()` → `dispose()`，
> 所以要么没走到、要么 dispose 之后 native 侧仍在冒候选。**代码里没有 5 分钟的定时器**，
> 嫌疑在 libwebrtc 自己的候选重采集。下一步：在 `onLocalCandidate` 出口按房间状态挡一道
> （治标），再查 PC 到底有没有真被释放（治本）。

**「要么没走到、要么 dispose 之后仍在冒候选」这个二选一是对的，答案是前者。**
后一半的嫌疑（`dispose()` 没先 `close()`）是错的：反编译 M150 的
`PeerConnection.dispose()`，它第一条指令就是 `invokevirtual close()`。
结论与修法见 `current_task.md`「已知坑」。

---

## 2026-09-06 归档：任务五落地时的活快照（设计稿 v3 落地前）

## 当前焦点

**任务一到任务五全部落地（2026-09-05）：骨架 → 协议层 → 状态机 → 信令与门面 →
媒体 → UIKit 与 Demo。`./scripts/test.sh` 六步全绿（64 个用例，纯 JVM），
并且已经在真机 OPPO PKD130 上装起来跑通了「免密登录 → WS 握手 → 建会议房 → 进房 →
媒体拉起 → 通话界面计时」。**

**媒体是通的**（2026-09-05 复核）。之前那句「AP 隔离导致 UDP 过不去、出声出画一次没验过」
**是错的**，三处证据：
1. 服务端日志里有 android 的上行：`上行 Track 先于 room.publish 到达，先攒着`
   （`video-local-video-…` / `audio-local-audio-…`）——**RTP 真的落到服务端了**。
2. 真机 logcat 里 `pub ICE 状态：CONNECTED` **与 `sub ICE 状态：CONNECTED` 都有**。
   ICE 到 CONNECTED 的前提就是双向 UDP 连通性检查通过——这条日志上一轮就打出来了，
   我一边贴着它一边下了相反的结论。
3. 记录页有来自 `bob`（= `ios-demo-bob`）的来电记录，iOS → Android 确实打进来过。

**当初为什么判错**（两个都要记住）：
- **`-ice-loopback` 是「additive」不是「exclusive」**：它是
  `settings.SetIncludeLoopbackCandidate(true)`，只是**多**宣告一个 127.0.0.1，
  192.168.1.12 那条 host 候选一直都在。看名字想当然了。
- **`adb reverse` 只影响信令**。ICE 的路径是从 SDP 里的候选自己谈出来的，
  跟信令走哪条通道无关——信令走 USB 隧道，媒体照样走 Wi-Fi。
- 网络也不是 AP 隔离：手机 → Mac 的 **TCP 通**（`nc` 返回 0，对照关闭端口返回 1），
  Mac → 手机 ping 0% 丢包。**只有手机 → Mac 的 ICMP 不通**，而我当初只 ping 了一下就下结论。

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
| `call-uikit` | 通话页（三段式）、控制条、九宫格算术、视图模型 reducer、**来电横幅 + 悬浮球** | 9 条纯 JVM 用例 + 真机截图 |
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
6. **UIKit 还欠的几件**（都不挡通话，属打磨）：双击放大某一格、本端预览挂进格子、
   「只引 Engine 自画 UI」的示范、客户端日志回传服务端。
7. ~~离房之后客户端仍在发 `room.ice_candidate`，服务端每 5 分钟回两条 `1203 not_in_room`~~
   —— **已修（2026-09-05）**，成因与结论见「已知坑」那条「离房要停媒体」。
   原文归档在 [current_task.archive.md](current_task.archive.md)。

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

**离房要停媒体，而离房是两步（2026-09-05 修，真机复验过）**
- **症状**：会议房离房之后，服务端每 5 分钟回**两条** `1203 not_in_room for_type=room.ice_candidate`，
  一路刷到没人管为止（真机日志里从 20:39 刷到 21:24，50 分钟）。两条 = pub 与 sub 各一条。
- **成因不在媒体层，在门面**：`driveMedia` 停媒体的判据原来是「before=joined 且 after=idle」，
  而离房走的是 `joined →(leave)→ leaving →(leave.ok)→ idle` **两次 input**，
  没有任何一次同时满足，于是 **`adapter.stop()` 一次都没调过**。
  两条 PeerConnection 就那么活着，开着 `GATHER_CONTINUALLY` 每 5 分钟重采一轮候选。
  「断线 → reconnecting →(被踢)→ idle」是同一个漏法。
  **现在的判据是「媒体还有没有人要」**：房间与通话只要还有一个不在 idle 就留着，
  两个都回 idle 才停——与「走了哪几步」无关。
- **`dispose()` 之前没 `close()` 不是成因**：反编译 M150 的 `PeerConnection.dispose()`，
  它第一条指令就是 `invokevirtual close()`。`IMPeerConnections.stop()` 里现在显式写了
  `close()` 再 `dispose()`，那是为了不依赖某个版本 dispose 的实现细节，**不是修这个 bug 的那一刀**。
  查这类问题**先确认那行代码到底有没有执行**，再去怀疑 native 层——这次直接跳到第二步，绕了远路。
- **出口还挡了第二道**：`onLocalCandidate` 里房间不在 joined 就丢弃。候选是从 native 的
  signaling 线程异步冒上来的，天生可能比 stop() 晚一拍；这一道也保证「哪天媒体层再漏一次」
  不会又变成服务端 WARN 刷屏。**判据只认 joined**：joining 时服务端还没把我们放进房，
  发上去同样是 1203。
- **两条都有单测钉着**（`EngineLoopTest`），逐条验过：只回退判据 → 停媒体那条挂；
  只回退出口守卫 → 候选那条挂。
- **`/code-review` 顺着这条线又挖出两个**（同一次提交里一起修了）：
  - **`room.leave` 被拒没人接**，房间永久停在 leaving。服务端在「会话已不在房间里」时
    回 1203（两人同时离房、或房间刚被「已空，已关闭」销毁就撞得上），
    而那恰恰说明我们已经不在房里了。卡住的代价：媒体停不掉（摄像头与前台服务一直开着）、
    再 leave 被 R1 拒成 2005、再 join 因「不在 idle」也被拒——**除非 logout，这台 Engine
    再也进不了房**。现在补了 `leave_failed`，与 `join_failed` 同形：归零 + `onRoomLeft`。
    **`onRequestFailed` 的每一个请求帧都该问一句「被拒之后谁把状态退回去」**，
    这已经是同一类洞的第三遍了（call / join / leave）。
  - **重连恢复被当成了新进房**。起媒体的判据是「不是 joined → 是 joined」，
    而 `resumed=true` 时房间机把 reconnecting 推回 joined，于是每恢复一次就重复发一整套
    audio+video：多两条 `room.publish`、pub 上多挂一组 transceiver，真机上 `startCapture()`
    还会在旧 capturer 没停的情况下再开一个摄像头采集（字段被覆盖，旧的再也停不掉）。
    **恢复的前提就是服务端那边的发布关系还在**，本来什么都不用补。

**竖排 LinearLayout 的默认 LayoutParams 是 `MATCH_PARENT`（2026-09-05 修，底部 tab 栏）**
- **症状**：Demo 底部三个 tab 的图标与文字全靠在各自格子的左边，第一个（拨号）贴着屏幕左缘，
  看起来像「按钮没均分」。**格子本身是均分的**（`weight = 1f`，实测各 240px），偏的是格子里的字。
- **成因**：`LinearLayout.generateDefaultLayoutParams()` 在**竖排**时返回
  `MATCH_PARENT × WRAP_CONTENT`（横排才是 `WRAP × WRAP`）。不带 LayoutParams 添进去的
  TextView 于是**撑满整格**，父容器那句 `gravity = Gravity.CENTER` 没有可居中的余量，
  字按 TextView 自己的默认 gravity 顶在左边。
- **规矩**：**要文字居中就把 gravity 设在 TextView 自己身上**，别指望父容器的 gravity。
  `DemoUI.titleBar` 与 Kit 里的 `IMFloatingBubble` / `IMCallView` 一直是这么写的，
  只有 `MainActivity.tabItem` 漏了——这也是为什么通话页的按钮文字一直是正的。
- **uiautomator 的 bounds 看不出这个 bug**：TextView 的边框本来就是满格，改前改后一模一样，
  **只能靠截图看**。别拿 bounds 当「布局对了」的证据。

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

**横幅 / 悬浮球（2026-09-05 落地）**
- **两者都是应用内浮层**，挂在当前前台 Activity 的 `android.R.id.content` 上，
  **不申请 `SYSTEM_ALERT_WINDOW`**（CONVENTIONS §8：敏感权限，影响宿主上架）。
  代价说在明处：**离开宿主 App 就看不见了**，通话本身不受影响。
- **没有前台 Activity 时的退路不一样**：来电退回全屏 Activity（App 在后台，这就是系统来电的做法）；
  已经收成小窗时**什么都不显示**——把用户硬拽回 App 才是错的，前台服务的通知还在。
- **`bannerExpanded` 必须放在 Kit 里、不能进 [IMCallViewState]**：状态里没有「用户看过横幅了」
  这回事，少了它的话展开成全屏后下一次刷新又被判回横幅，界面来回跳。
- **接通之前不许收小窗**：拨出中收起来，剩一个不会动的小球，用户既不知道对方接没接，
  也想不起来怎么挂断。`结束`时还要**主动把小窗展开**，否则「对方忙线」藏在 60dp 的球里没人看见。
- **拖动与点击要用 `touchSlop` 分开**，否则手指抖一下就展开全屏。拖完**只吸左右不吸上下**：
  上下贴边会撞到状态栏与导航条。

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

