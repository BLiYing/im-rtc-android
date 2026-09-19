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

## 2026-09-06 · 九宫格拉齐、前后台记账、锁屏黑格、ICE 重启（从「当前焦点 / 上一轮」退休）

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


---

## 从 current_task.md 搬入（2026-09-07，为「Pixel 登录」那轮腾地方）

### 发起群通话当场闪退

**发起群通话当场闪退（2026-09-07 修）**，`./scripts/test.sh` 六步全绿。

`IMCallGridView.apply` 在「同一批格子、只是尺寸变了」那条路上直接改 `columnCount`，
撞上 GridLayout 的一条隐藏约定：格子是不写行列的（`spec(UNDEFINED)`），
**但它每次 measure 都会在 `validateLayoutParams()` 里把它们改写成具体下标**
（`columnSpec` 变成 `[2,3)`）。于是「在场子视图的最大下标」= 上一版的列数，
下一次把列数**调小**，`Axis.setCount` 当场抛 `IllegalArgumentException`。

**不用转屏就能撞上**：第一轮 `render` 早于第一次 layout，只能按默认 `aspect = 0.7` 估
（9 人 → 3×3）；量到真尺寸那一轮是 0.48（控制条的下 padding 还没生效）→ 2×5。
`columnCount = 2` 而在场最大下标是 3 —— 发起群通话就是这么炸的。

修法是**先把每个格子的 spec 退回 `spec(UNDEFINED)` 再改行列数**（`setLayoutParams`
会让 GridLayout 重算最大下标），一个 `SurfaceView` 都不摘、不闪。

顺带修掉同一函数里的第二个洞：「没变就不重挂」的判据原先比的是**上一次记下的 `tiles`**，
而格子会被 `pinFull` / `mountInPip` 从格子里摘走挂到全屏画面或小窗。改成比**在场的子视图**
（`childrenAre`），否则视频版式切回九宫格时会认成「什么都没变」，格子再也回不来——一屏空网格。

**没做**：真机复验。这条要在 OPPO PKD130 上真的发起一次 9 人群通话才算数，
单测只钉住了前提（`同一批人列数也会变小`，纯 JVM）。

## 2026-09-07 · 会话恢复后重新协商上行 + 红按钮永不静默（从「更早」退休）

**会话恢复之后重新协商上行 + 红按钮永不静默（2026-09-07）**，`./scripts/test.sh` 全绿。

| 改动 | 为什么 |
|---|---|
| `IMMediaAdapter.restartPubICE()`（新）+ `IMPeerConnections.markIceRestart()` | **本仓原先是三端里唯一不对称的**：ICE 重启只活在媒体实现内部，引擎调不到；iOS/Web 的适配器早有这个方法。补齐后三端同名 |
| `RoomStateMachine` 新增 act `restart_pub_ice`（+ `ROOM_ACTS`） | 与 iOS/Web 对齐：发帧是 Engine 的事，媒体层不认识信令，也不知道此刻房间在不在 joined |
| `onConnected` 里 `resumed==true` → 重协商上行 | 协议 §1.4 写着「客户端的 pub PC 若已失效则重发 `room.offer{pc:"pub"}`」，一直没实现。只挂在「PC 判 FAILED 那一刻」是不行的——网一断信令也断，房间已是 reconnecting，动作会被拒且不进缓冲 |
| `IMCallKit.hangup()` 的 `Action.NONE` 分支不再是 `Unit` | 用户按挂断的意图没有歧义：把我弄出去。认不出该发哪种结束帧 = 本地记账已经和服务端对不上，那时唯一正确的动作是**本地收场**，不是什么都不做 |

**媒体实现里那条即时重启保留**（`FAILED` → `createOffer(iceRestart=true)`）：信令还活着时它是对的。

**没做 / 已知限制**：本轮**没有任何真机复验**——ICE 那条尤其要真的拔网线才验得了。
Android「无法挂断」的**根因未定**（Android 不上报日志到 logsink，只有 logcat），
只做了「红按钮永不静默」的兜底；服务端补发一落地，那个僵尸态本身就不该再出现了。



---

# 2026-09-08 搬入：小窗崩溃修复之前的几轮（均已完成并全绿）

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



## 2026-09-08 之前的「当前焦点」（review 修复那一刀挤下来的）

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


## 2026-09-08 code review 三条（被静音那一刀挤下来的焦点）

**会话没了却不给宿主收场信号（2026-09-08）**，`./scripts/test.sh` 六步全绿。
分支 `fix/parity-room-left`（worktree `../wt-android-parity`，**叠在 `fix/code-review-0908` 之上**）。
**未真机复验。**

这一条是 **Web 那轮 `/code-review high` 的跨端对账**查出来的，**三端同源**，本端也中招。

`IMRoomMachine.resume(ctx, resumed = false)` 只是把房间清成 IDLE，**一个事件都不抛**。
有 call 的场合还有 `onCallEnd(network)` 兜着（不变量 I8），可**会议是直接 joinRoom 的、
压根没有 call**：房间机悄悄回了 IDLE，而界面还显示着「会议中」、计时器还在走，
用户完全不知道自己已经掉出去了。更要命的是一个结束类回调都没抛 → 门面的 leave 那组回调
不命中 → `media.stop()` 永远不调用，**摄像头与前台服务一直开着**，
上一轮的 PeerConnection 还会被带进下一次进房。

改法：`IMEngineMachine` 抽出 `dropLostSession`（`handleHelloOk` 的 `resumed=false` 分支与
`session_unrecoverable` 共用它）——有通话就抛 `onCallEnd`（**唯一出口，不再补 onRoomLeft**，
否则宿主记两遍账），没通话但在房里就补一条 `onRoomLeft`。
Web 的 `engineMachine.dropLostSession`、iOS 的 `IMEngineMachine.dropLostSession` 是同一段。

**新增用例 5 条**（`statemachine/LostSessionTest.kt`）：会议的两条收场路径
（`resumed=false` 与 `session_unrecoverable`）、有 call 时不重复抛、
idle 时不凭空抛、`resumed=true` 一个字不变（一致性向量
`reconnect_not_resumed_synthesizes_call_end` 钉住的那条行为没动）。

---

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

**code review 的三条（2026-09-08）**，`./scripts/test.sh` 六步全绿、173 条用例
（engine 97 / webrtc 9 / uikit 41 / demo 26）。分支 `fix/code-review-0908`
（worktree `../wt-android-review-fixes`）。**未真机复验。**

### 1. 迟到的关闭事件会把一条好端端的连接拆掉（最重的一条）

`closeAndReconnect` **自己先调一次** `handleClosed`，而 transport 的 `onClosed` / `onFailure`
随后**还会再调一次**——`transport.close()` 只是发个关闭帧，OkHttp 一定还会回调，
中间没有任何「已经收过场了」的闩。

网络假活时（也就是心跳超时那条路）第二次回调可能**晚到好几分钟**，那时新连接早已连上：

```
心跳超时 → closeAndReconnect 就地收场 + 排重连 → 1s 后重连成功、connected=true
   ⋯ 几分钟后 ⋯
旧 socket 的 onFailure 终于冒出来 → handleClosed 又跑一遍：
  wasConnected=true → 多抛一条假 onDisconnected（界面写「正在重连」而连接好好的）
  failAll          → 把新连接上在飞的请求全掐掉
  connected=false  → scheduleReconnect → openSocket 开出**第二条 socket**
  同 uid 同 device_id → 服务端按顶号踢掉一条 → **假的 onKickedOut(TAKEN_OVER)** → 用户被踹回登录页
```

改法：**认代际**。每开一条 socket `generation += 1`，`TransportListener` 带着自己那一代，
`handleClosed(code, reason, from)` 只认没收过场的那一代（`closedGeneration` 是闩）。
`onOpen` / `onText` 也一并挡掉旧代——旧 socket 上迟到的帧是上一条会话的东西，不能喂进状态机。
`stop()` 顺手把当前代闩上，免得 logout 之后那条回调又排一次重连。

> iOS 不会踩：`IMURLSessionWebSocket` 有个 `closed` 标志，保证每条 socket 只回一次 onClose。
> 这里等价的做法就是认代际。

### 2. offer 抢在 `setLocalDescription` 前面上线路

`createOffer` 的 `onCreateSuccess` 里，`setLocalDescription` 还是异步的（回调在 WebRTC 的
signaling 线程上），紧挨着就 `events.onLocalSdp(...)` 把 offer 发出去了。局域网 / 本地 SFU 下
`room.answer` 几毫秒就能回来，而本端描述可能还没设上：`applyRemoteSdp` 看到
`signalingState()` 是 STABLE 而不是 HAVE_LOCAL_OFFER，就把它当重复应答**丢掉并 `abortOffer`**。
**这一路发布就此协商不出去**——对端看得见人、收不到流，一条报错都没有，
而且没有任何东西会重新驱动它（要等 ICE 进 FAILED 才有下一次机会）。

改法：`onLocalSdp` 挪进 `setLocalDescription` 的 `onSetSuccess`，失败那支放闸。
`LocalSdpObserver` 因此没人用了，一并删掉（别留死类）。
**iOS 与 Web 本来就是 await 完才发的**，这里是对齐它们。

### 3. `resume` 无条件把 `reconnecting` 推成 `joined`

`disconnected` 会把 `JOINING` 也推进 `RECONNECTING`，而那次 `room.join` 还在飞、
服务端从没受理过我们。恢复后本端以为在房里 → 每帧换回 1201/1203，
重新 join 又因「不在 idle」拒 2005。

本端目前靠 `onRequestFailed` 的 `join_failed` 能兜住（`failAll` 是同步回调，
排在 `onDisconnected` 前面），**但那是时序凑巧**——iOS 同一段代码就因为多两跳 actor 翻过车。
改法：房间上下文加 `didJoin`（只由 `room.join.ok` 置位），`resume` 据它分辨来路：
真进过房才回 `JOINED`，否则**重发一次 `room.join`**（房号房票都在手上，攒下的意图照旧留着）。
**三端同一份，不依赖谁先谁后**（iOS 同轮一起改）。

**向量没动**：两条 reconnect 向量的初始态都是 `room: joined`，`didJoin` 不影响它们。
向量跑法里补了一句种子——**是种子不完整，不是实现变了**。

**新增 10 条用例**：`StaleSocketCloseTest`（4 条，`FakeTransport` 现在留下每一条 socket 的
listener，才测得出「旧的迟到」）、`RoomResumeTest`（6 条）。


## 2026-09-09 之前的「当前焦点」

**「呼叫中按的静音会丢」——真机 2026-09-09 抓到的隐私问题**，
`./scripts/test.sh` 六步全绿、183 条用例。分支 `fix/mute-before-publish`。**未真机复验。**

alice（Android）发起群呼，在「正在呼叫…」阶段按了静音，bob 接通后**照样听得见她说话**，
而 alice 界面上写着「已静音」。日志：

```
00:09:59.7  call.invite
00:10:01.0  WARN 没有 audio Track 可以开关   ← 按静音，被丢掉
00:10:04.8  call.connected
00:10:05.0  room.publish ×2                ← 这时才发布轨道，默认开着
00:10:05.0  WARN 没有 audio Track 可以开关   ← onRoomJoined 的补救也失败
```

**根因：`setMuted` 把两件事绑死了。** 关本端轨道不需要 `track_id`（那是服务端分配的），
只有 `room.mute` 帧需要；而原先拿不到 track_id 就整个早退，**连本端也不关**。
偏偏「拿不到」正发生在最该静音的时候。Kit 的 `toggleMic` 又是无条件翻界面的，于是界面撒谎。

`onRoomJoined` 那道补救方向对、时机错：它跑在 `room.join.ok`（05.013），
而 `room.publish.ok`（05.04）还没回来，`publishTrackIds` 仍是空的。

**改法三条**：

1. `setMuted` 拆开——本端 `media.setMuted` **无条件执行**，帧只在有 track_id 时发；
2. 意图存进 [IMMuteBook]，`room.publish.ok` 拿到 track_id 那一刻补做（本端 + 帧都补，
   因为轨道是刚现造的、默认开着）；判据是 **track_id 从无到有**，不是「表里有」，
   否则每帧下行都会重发一遍；
3. 通话结束时意图跟着作废，别漏到下一通。

界面撒谎那条**不用单独修**了：`setMuted` 现在总能落地意图，界面就不再是谎话。

**只有 Android 中招**：iOS 与 web 的 `setMuted` 都是先无条件 `media.setMuted`、
guard 只挡信令帧，所以对端听不见你——这也正好解释了真机上的不对称
（bob 在 web 静音生效，alice 在 Android 不生效）。

**顺带拆了两刀**（体量门禁 621 > 600，且明令不许放宽阈值）：
静音这一摊抽成 `IMMuteBook`（纯逻辑、可单测），协商帧转发抽成
`IMNegotiationFrames.kt` 的自由函数（它不碰门面任何状态）。`IMCallEngine.kt` 回到 587 行。

**新增 5 条用例**（`MuteBeforePublishTest`）。把 `setMuted` 注回旧逻辑，其中 2 条立刻红。

## 2026-09-11 下午 · 真机报的五个问题（从「当前焦点」退休）

**2026-09-11 下午：真机报的五个问题逐个修，直接在 main 上改（未提交）。本仓三处：**

| # | 现象 | 根因 | 本仓改了什么 |
|---|---|---|---|
| 2 | PKD130 启动即崩，每次重启再崩（`Starting FGS with type microphone … requires RECORD_AUDIO`） | Android 14+ 前台服务的 microphone 类型要求 RECORD_AUDIO **已授权**。麦克风权限被收回后在来电页点开摄像头 → `ensureCapture` 起服务 → `onStartCommand` 里抛出去 → 系统重投启动 → 循环崩 | `IMForegroundTypes` 按真实授权拼类型；`start()` 一样都没有就不起；`onStartCommand` 兜住异常 `stopSelf()` |
| 3a/3c | iOS 九宫格竖屏源左右黑边 / 变成竖直画面（本仓正常） | 9:16 源放正方形格子恰好压在 0.5625 阈值上，iOS 格子边长是小数、差 1px 就判成 FIT；本仓格子边长是整数像素 | `IMVideoFit.FILL_TOLERANCE = 0.01`，与 iOS 同值（对齐，不是修 bug） |
| 4a | 视频来电横幅接听键是摄像头图标，点开来电页是听筒 | 横幅 `render` 按 `mediaType` 换图标，违反 UI_SPEC「phone · 来电页、来电横幅」 | 恒为 `PHONE` |

`./scripts/test.sh` 全绿（6 步），新增 `IMForegroundTypesTest` 4 条、`VideoFitTest` 2 条。**没上真机**。
Web（3b 本端格子没画面）与 iOS（3a/3c、4a、4b 来电页看不见自己）在各自仓的 current_task。

**悬而未决**：本仓来电页点开摄像头会当场弹摄像头权限框，交互稿 §01 说响铃时什么都不申请——等用户拍板，没动。

上一刀（接听时摄像头权限 / 摄像头关着不采集）已合 main、PKD130 验过，细节看 `git log`。

**iOS 的 simulcast 缺失已决定暂缓**（2026-09-09），结论在 `../im-rtc-ios/current_task.md` 的「已知坑」。

---

## 2026-09-11 精简前全文（✅ 已完成项与冗长细节从 current_task.md 移出，原文照录）

# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log` 与
> [current_task.archive.md](current_task.archive.md)（只读归档，2026-09-07 又搬入一段）。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；**界面以设计稿 v3 为准**：
> `../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html`（令牌 / 图标 / 组件红线）与
> `RTC_CALL_UX_FLOWS.html`（权限 / 小窗 / 互换 / 加人，**§08 是 Android 的六处差异**）。
> **两稿已升到 v3.1**，推翻了 v3 的六条，冲突时以 v3.1 为准。


## 当前焦点

**2026-09-11 夜：修「Android 看 iOS 重开摄像头，那格画面出来又刷新一下」。直接在 main 改，未提交，等真机。**
上一刀「后台重连节奏」已提交 `2c9c2fe`，**还没真机验**（步骤见「下一步」）。

**根因**（19:17 真机日志）：Kit 按 `onUserVideoAvailable(true)`（`room.track_muted`）揭示格子，而信令比新画面早 450–900ms
（`远端视频出帧（断流 Xms 后）` 晚于它）。渲染器按 uid 整通复用，Surface 上留着关摄像头前的最后一帧，`EglRenderer` 不清也不重画，
`onFirstFrameRendered` 每次 `init` 只来一次——于是先露旧帧、新帧到了跳一下。不是编码 / 分辨率问题（720p 没减轻，与此吻合）。

改了什么：
- Engine：`media/IMMediaAdapter.kt` 加 `awaitFirstVideoFrame(uid)`（默认空实现）；`IMCallEngine` 在 `dispatchAll` **之后**按 `videoTurnedOn(emit)`（`IMVideoTurnedOn.kt`）调它——先让 Kit 挂起、再等帧，顺序反了首帧会被吞。
- webrtc：新增 `IMFirstFrameGate.kt`，`addFrameListener(…, 0f)` 一次性监听，真 swap 之后才报 `onFirstVideoFrame`，与 `onFirstFrameRendered` 去重。缩放那段拆成 `IMVideoFitter.kt`（适配器 600→549 行）。
- Kit：`IMCallViewState.Member.videoPending` / `showsVideo`（从无到有才挂起，已在播时再报 true 不挂起）；`IMCallView` 两处只认 `showsVideo`；`IMKitListener` 2 秒兜底。
- 回调表没加项，`onFirstVideoFrame` 语义扩成「重开后再报一次」（server 设计文档 §7.5 已改）。
- 单测 `EngineLoopTest`、`CallViewStateTest` 各一条；`./scripts/test.sh` 全绿（6 步）。

日志：`远端画面重开后新帧上屏 key= waitMs=`（每次重开 1 行）、`新画面 2000ms 没上屏，照样揭示 uid=`（不该出现）。
**限制**：首次进房也变成「首帧上屏前露头像」（原先是黑底）；iOS / Web / 桌面没做「重开再报」；SFU 开摄像头不主动要关键帧（次要，没做）。
上一轮的接收侧诊断（`IMFrameGapTracker` / `IMRemoteVideoDiagnostics`，每帧多一次 JNI 回调）还挂着，**真机确认修好后拆掉**。

**iOS 的 simulcast 缺失已决定暂缓**（2026-09-09），结论在 `../im-rtc-ios/current_task.md` 的「已知坑」。

### 体量欠账（**下次动它之前先拆**）

`IMCallEngine.kt` **599 行**、`IMSignalConnection.kt` 598、`IMCallView.kt` 591、`IMCallKit.kt` 589 都贴着闸。
`IMSignalConnection` 还能挪的：socket 代际那套（`generation` / `closedGeneration` / `TransportListener`）连同心跳。


## 下一步

### 真机验收（本轮，最优先——上面那批还没有人验过）

0. **对端重开摄像头不再刷新一闪**（本批）：群通话里 iOS 频繁开关摄像头，本机上 iOS 那格应是「头像 → 直接新画面」、不闪旧帧；
   logcat 有 `远端画面重开后新帧上屏 waitMs=`（应在几百 ms），不该有 `没上屏，照样揭示`。报通话时间。
1. **背景重连节奏**（`2c9c2fe`）：Demo 登录后切后台放置约 1 分钟（ColorOS 手机最好，会自动杀后台 socket），
   `adb logcat | grep -i signal`（或按 `IMRTCLog` 的 tag 过滤 `"signal"`）应该看到断开→重连间隔
   走 1,2,3,3,3…秒（约每分钟 10 次重连，不是原来的 20 次）。
2. 期间把 App 切回前台：日志里应该立刻出现一次重连（不是等到下一个定时器到点），且随后走的退避档位归零。
3. 通话中把 App 切到后台（前台服务还在跑）：确认走的也是后台节奏（第 2 步同一套判据）。
4. 以上均为**代码走查 + 单测覆盖**，无真机实测；ColorOS 秒杀 socket 的具体间隔是否稳定在 3 秒、
   服务端 5 秒窗口是否真的因此不再错过，都还没有实机数据。

**上一轮挂着的**（1v1 视频）：控制条收起后点底部该叫回控制条、不该静音/挂断；挂断后结束画面标题栏不淡掉；
开局清晰度——服务端日志进房 1 秒内出现 `上行层已接入 … rid=h`、整通没有 `layer=h live=False`
（要配服务端 `worktree-fix-bwe-burst-and-ratchet` 一起验）。自动隐藏那刀在分支 `worktree-fix-autohide-never-fires`，未验收。

### 真机验收（**这一整批一条都没验**）

按风险排序，前两条不过其余不用看：

1. **语音判定**（server）：`SPEECH_DEBUG=1 ./scripts/dev.sh` → 不说话时是不是真的不亮了；
   说话时亮不亮、条高随音量变不变；把 `margin` 的实测值发回来核门槛。
2. **说话指示器三态**：别人的格子「关 / 开着没说话 / 正在说话」，自己那格只有前两态；
   1v1 不显示说话但显示麦克风开关；多人同时说话每格各亮各的。
3. **Android 呼叫中按静音**：**接通前**按静音 → 对方接 → 确认对方听不见，
   且日志里有 `补做发布前攒下的静音`。（上次验成了「接通后按」，没走到修复那条路。）
4. **iOS 镜像**：翻到后置摄像头，自己看到的字不该是反的。
5. **web 挂断后重进**：bob 进群通话 → 挂断 → 再邀请回来 → 这次该看得到他的画面。
6. **通话时长**：群通话里中途加入的人退出后，记录里的时长是他自己那段，不是整通。
7. 之前那七条 code-review 修复也都没验（故障注入手册 `docs/ops/FAULT_INJECTION.md`）。

### 待办

- **下一个任务（已和用户对齐）**：四端扫一遍**静默失败点**——早退分支、被吞掉的异常、
  静默空实现。今天两个 bug 全是这一类（一句不吭的 `return`，界面/日志/报错三个观测面
  同时是瞎的）。只给真正可疑的加日志，判据卡死到「正常时一通电话最多出现一次」。
- **desktop 端说话指示器没做**：它 `MediaAdapter` 唯一实现是 `tests/FakeMediaAdapter.h`，
  libwebrtc 还没接进来，九宫格本身就是 ⬜。要等媒体面落地。
- **`CLIENT_PARITY.md` 没更新**：真机验完再改；验之前 iOS/Android 停在 🟡，不写 ✅。
- **web 端 `getUserMedia` 那类失败仍可能静默**：日志回传够不到浏览器 console。


## 已知坑 / 限制

- **`SignalConnectionTest` 里用 `scheduler.advance(N)` 模拟「连接活了很久」要小心心跳超时**：
  心跳每 `pingSec`（默认 15s）一次，`nowMs() - lastInboundMs > pingSec*2`（30s 静默）会自触发
  `closeAndReconnect("heartbeat timeout")`，如果没有配合喂 `PONG`，`advance` 到 30s 以上会打进这条
  分支，污染退避档位断言。要么把模拟时长压在 `2×pingSec` 以内，要么显式 `transport.deliver(PONG, "")`。
- **仓根 `temp_verify.py` 是另一批任务共用的活文件**，别的会话/后台任务可能正并发在改；
  本仓写自己的小验证脚本时（如 `temp_verify_reconnect_pacing.py`）optional 单独建文件，别往那份共享脚本里插，
  免得竞争写丢内容。
- **PKD130（ColorOS / Android 15）上 `pm revoke` 被挡**：`SecurityException … REVOKE_RUNTIME_PERMISSIONS`。
  要测「没摄像头权限」只能去系统设置里手动关，或先打开开发者选项的「USB 调试（安全设置）」。
- **摄像头意图必须在进房之前给 Engine**（`IMCallKit.syncCameraIntent`）：晚了 `publishDefaults` 已经把视频轨发出去、采集也起了。
  接通后才打开的摄像头靠 `publishCameraIfMissing` 补发，补发后会多一条无害的 `room.mute muted=false`。
- **关摄像头停的是采集**（2026-09-11）：进房前关 = `stopLocalPreview`（已发布的不碰），通话中关 = `setMuted` → `setCapturePaused`。
  **进房前切到后台采集不停**（后台自动 mute 只管已发布的轨道）；通话中重开会让本端预览重新出第一帧，可能闪一下。

- **2006 的阈值「3」没经过真机校准，而且它现在抛出来也没人接。** 两件事一起记（2026-09-09）：
  - **阈值待校准**：libwebrtc 判 `failed` 约 30 秒一轮，连续 3 次就是**一分半以后**宿主才知道，
    用户多半早挂了。真机弱网跑过之后很可能要调成 2 次、或者改成按时间而不是按次数。
    四端 libwebrtc 版本还不一样（iOS M152 / Android M150 / 桌面 M150 / Web 是浏览器自带），
    `failed` 的触发时机不见得对得齐——这条只有真机验得出来。
  - **目前它在界面上等于不存在**：四端 Kit 的错误出口都只认几个码
    （Web uikit 2 个、iOS `default: break`、Android `when` 没有 `else`），2006 落地即消失。
    所以现在**回归风险≈0，价值也≈0**，要等 Kit 那几个兜底补上才通。
  - 弱网环境暂缓搭建（2026-09-09 决定），有条件再做。

- **`maxBitrateBps` 是「h 一层」的数，不是上行总量。** 推 simulcast 时上行真正要
  `l+m+h`（720p = 2.15Mbps）；服务端 `bwe.go` 的 `bitrateHigh=1.5M` 算的是**下行**预算，
  那边每个订阅者只收一层，两个数不是一回事。混为一谈的后果是
  `SimulcastRateAllocator` 自底向上分配时给顶层分 0 bps——**开局只有 `l` 层出包**，
  服务端日志是 `上行层存活性变化 layer=h live=False`，订阅端看到 320×180。
  已由 `simulcastUplinkBudgetBps` + `PeerConnection.setBitrate` 播种兜住。

- **iOS 端没有真 simulcast，所以「两端都糊」不是对称的。** `stasel/WebRTC 152.0.0`
  **没打进 `RTCVideoEncoderFactorySimulcast`**（头文件与符号都不存在，`nm` 验过），
  iOS 发出去的永远是单层 H264。于是 SFU 的降层只砸 Android：全量日志里 H264 轨道
  101 条全是 1 层，下行 21 条即使 `bw_cap=l` 也照发 h；VP8 那边 32 条有 20 条真降到了 `l`。
  **排查「为什么只有 Android 糊」时先想到这条**，别往本仓的编码参数上找。

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
- **前台服务类型也不能超出已授权的权限**（Android 14+）：microphone 要 RECORD_AUDIO、camera 要 CAMERA 已授权，否则
  `startForeground` 抛 `SecurityException`。抛在 `onStartCommand` 里系统会重投启动 → **循环崩**；
  而 `startForegroundService` 之后不调 `startForeground` 也崩——所以 `start()` 里先判（`IMForegroundTypes.granted`）。
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
- **SDK 版本号只改 `call-engine/.../IMCallEngineVersion.kt` 一处**（2026-09-11 五端统一 1.0.0）：握手 `sdk` 默认 `android/1.0.0`
  （原先光秃的 `"android"` 服务端日志分不出版本），Demo「关于」读它；**`demo/build.gradle.kts` 的 `versionName` 是写死的，发版要手动同号。**

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

## 2026-09-15：forceEnd / 后台重连节奏 / 1v1 视频细节——真机验收清单（从 current_task.md 移出，未完成，非本次改动）

被 2026-09-15 稍晚的「宿主对接 M1→M2→M8」覆盖掉之前，`current_task.md`「下一步」还挂着这些没验完的项，
先搬到这里免得丢：

0. **`forceEnd`**：挂断被拒时看门狗兜底已于 09-15 10:08 PKD130 验过（服务端故障注入拒掉 alice 的 hangup
   10:08:15.278 → 10:08:18.236 `f-1` 补发被受理、通话结束、alice 回首页）。
   还没验：断网（飞行模式）后按红键——3 秒后 logcat 有 `强制收场` + `没有信令连接`；
   拨号后立刻按红键（invite 还没回）——被叫不再一直响（Web 端 10:09 已验同一路径）。
   联测做法（adb 坐标、故障注入 curl）见 server 仓 `scripts/dev.sh` 的 `FAULT_INJECTION=1` 与 `/v1/dev/faults`。
1. **后台重连节奏**（`2c9c2fe`）：登录后切后台约 1 分钟（ColorOS 最好），`adb logcat | grep -i signal`
   断开→重连间隔走 1,2,3,3,3… 秒（约每分钟 10 次，不是原来的 20 次）。
2. 期间切回前台：立刻重连一次（不等定时器），退避归零。通话中切后台（前台服务在跑）也该走后台节奏。
   ColorOS 秒杀间隔是否稳定、服务端 5 秒窗口是否接得住，都还没实机数据。
3. 1v1 视频上一轮：控制条收起后点底部叫回控制条（不静音 / 挂断）；挂断后结束画面标题栏不淡掉；
   开局清晰度——服务端进房 1 秒内有 `上行层已接入 … rid=h`、整通无 `layer=h live=False`。
   自动隐藏那刀已合入 main（`f7cfb05`），未验收。
4. 跨端老批次（含本端「接通前按静音」）清单见 `../im-rtc-server/current_task.md`「跨端待验」。

**体量欠账（2026-09-15 早）**：`IMSignalConnection.kt` 598、`IMCallView.kt` 591、`IMCallEngine.kt` 583、
`IMCallKit.kt` 549。`IMSignalConnection` 还能挪：socket 代际（`generation` / `closedGeneration` /
`TransportListener`）连同心跳。（后续宿主对接改动把 `IMCallEngine.kt`/`IMCallKit.kt` 又往上顶了一截，
见 `current_task.md` 最新的体量段落。）

---

## 2026-09-15（第二轮）：四端 API 命名对齐 + 宿主对接 M1/M2/M8（已提交 `cf8c18f` / `7e02e50`）

2026-09-16 从 current_task.md 移出，原文照录（当时写的「未提交」已过时）。

### 当时的「当前焦点」

**2026-09-15（第二轮）：四端 API 命名核对，本仓不一致项最多，逐条对齐。未提交；`./scripts/test.sh` 全绿（6 步，单测 engine 165 / webrtc 36 / uikit 67 / demo 26）；真机未验（本轮只是签名/命名改动，编译 + JVM 单测覆盖，媒体行为未变不需要重验）。**

对齐结果（对照 Web `packages/call-engine/src/events.ts` 与 iOS `IMCallEngineDelegate`）：
- 删 `onCallMediaTypeChanged`/`mediaTypeChanged`（确认无调用方、协议无此帧）。
- `onDisconnected(code, reason: String)` → `onDisconnected(code, willReconnect: Boolean)`：判定挪到 `IMSignalConnection.handleClosed`，抛回调前当场算好（新增纯函数 `IMReconnectPolicy.willReconnect`），`IMKitListener` 顺带把「LOST」判据从硬编码 `code==4403` 换成 `!willReconnect`——4401 用尽也能立刻判对了，不用等下一条 onKickedOut。
- `onError(code, message)` → `onError(code, name, message)`：`name` 由 `IMEventDispatcher` 按 `IMErrorCode.fromCode(code)` 反查兜底，调用方不用逐个改。
- `onCallBegin` 参数顺序改成 `(callId, roomId, mediaType, isGroup, role, caller, chatGroupId, userData)`。
- `onFirstVideoFrame(uid)` → `onFirstVideoFrame(uid, trackId)`：`IMWebRTCAdapter` 新增 `trackIdFor(uid)`，远端反查 `trackOwners`、本端预览用 `videoTrack/previewTrack` 自己的 id，拿不到给空串不造假值。
- `onCallEnd` 的 `reason: String` → `reason: IMCallEndReason`：枚举从 `protocol` 包移到 `com.imrtc.engine`（对齐 `IMKickedOutReason` 的位置）转正为公开 API，`wire` 公开、`canBeConnected`/`durationPositive`/整个 companion 收成 `internal`；枚举顺序本来就与规格一致，未改。**本仓没找到为它生成代码的脚本**（类注释说"从向量生成"，但仓内 `scripts/` 与 server 仓都没有对应生成器），这次是手改的产物，回报见下方③。
- `IMCallEngine.call`/`inviteMore` 与 `IMCallKit.placeCall` 的被叫参数统一改名 `calleeIds`（纯改名，Kotlin 位置参数不受影响，Java 侧本来就是位置调用）。
- `openMic/closeMic` → `openMicrophone/closeMicrophone`：确认麦克风轨道进房时无条件发布（`IMLocalPublisher.publishDefaults` 音频那支不看摄像头式的「有没有发」意图），所以不需要照 `openCamera` 那样补 `publishIfMissing`；补了一条「关闭又打开、以最后一次为准」的单测。
- 本端预览 `startLocalPreview(): cid` + `attachLocalView(cid, view)`：**评估后没做**，原因见「下一步」。

- **M1（call-engine）**：`CallFrames.INVITE/INCOMING` 加 `chat_group_id`，`CONNECTED` 加 `caller`/`chat_group_id`/`user_data`；`IMCallContext` 记 `caller`/`chatGroupId`/`userData`，`handleConnected` 取 `call.connected` 的值、为空回落到 `call()` 选项 / `call.incoming` 记下的值；新增 `IMCallOptions`（`@JvmOverloads`：isGroup/chatGroupId/userData/timeoutSec）与 `call(userIds, mediaType, options)`（校验拆在 `IMCallInvite.kt`，超限走 `IMCallOptionsGuard`，本地先拦、不上线路、与「callee 里有自己」同一出口）；`IMErrorCode.INVITE_DENIED=1409`；`onCallReceived`/`onCallBegin` 改签名加 isGroup/caller/chatGroupId/userData（**不留旧签名**）。
- **M2（call-uikit）**：新增 `IMInviteContext`、`IMInviteCandidate` 扩字段（avatarUrl/subtitle/selectable/unselectableReason）、`IMInviteMemberProvider`（loadCandidates/presentInvitePicker/canInvite）、`IMPickedCallback`；`IMCallKitConfig` 加 `inviteMemberProvider`/`allowsManualUidInput`；`IMInvitePicker` 按 `IMInviteFlow` 的优先级（宿主接管 > provider > 静态 inviteCandidates > 空态）重写：300ms 防抖、generation 计数作废旧请求、滚到底翻页、加载中/失败(重试)/10s 超时三态、participantUids 已在通话中不可选、selectable=false 置灰。`IMCallViewState` 加 `chatGroupId`/`userData`；`incoming`/`outgoing`/`begin` 三个 reducer 带上这两个字段（`begin` 用可空参数「不传不改」，保留旧 5 参数测试调用点）。
- **M8（本仓部分）**：`IMCallEngine.joinCall` 已有（M1 前就在）；新增 `IMCallKit.joinCall(callId)`（直接进 CONNECTING「接通中…」，`IMCallViewReducer.joining`）；`IMJoinCallState.joining` 记状态，`IMKitListener.onError` 按它把 1409 分成「对方暂时无法被邀请」（加人）/「无法加入该通话」（加入）两句文案，其余 join 失败码统一给后一句。
- **Demo**：`DemoInviteProvider`（真实联系人排前 + 40 个假成员分页，`fail`/`slow` 模拟失败/超时）挂到 `kitConfig.inviteMemberProvider`；`DialerScreen` 加「加入进行中的群通话」卡片（call_id 输入框 + 按钮）；群呼带 `chatGroupId="demo-group"`；`JavaApiCheck.java` 补 `IMCallOptions`、新 `onCallBegin`/`onCallReceived` 签名、Java 版 `IMInviteMemberProvider`、`IMCallKit.joinCall`。

**体量**：`IMCallEngine.kt` 599、`IMSignalConnection.kt` 600（2026-09-15 命名对齐这轮，`onDisconnected` 的 `willReconnect` 判定逻辑已经抽成 `IMReconnectPolicy.willReconnect` 纯函数才压住）、`IMCallKit.kt` 584、`IMCallViewState.kt` 541、`IMCallView.kt` 591——**都在红线内，`IMCallEngine.kt`/`IMSignalConnection.kt` 基本没余量了（600 已到硬顶）**，下次改这两个文件之前先想好拆哪块，别指望还能塞得下新逻辑。


## 2026-09-17（SDK 1.0.0 公网发布后精简）：精简前全文

> 2026-09-17 SDK 1.0.0 四端公网发布、五仓推送之后，`current_task.md` 整份重写成一屏快照；下面是重写前原文照录（标题降两级，正文未改）。

### Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：就地覆盖、不追加。历史见 `git log` 与 [current_task.archive.md](current_task.archive.md)（末节「2026-09-15（第二轮）：四端 API 命名对齐 + 宿主对接 M1/M2/M8」）。
> 规范 [CONVENTIONS.md](CONVENTIONS.md) · 分期 server `docs/design/RTC_CALL_DESIGN.md` §10 ·
> 界面以设计稿 **v3.1** 为准：`../im-rtc-server/docs/design/sketches/RTC_CALL_UI_SPEC.html` / `RTC_CALL_UX_FLOWS.html`（§08 是 Android 六处差异）。
> ✅ 状态只写在 `../im-rtc-server/docs/CLIENT_PARITY.md`。

#### 当前焦点

**2026-09-16（第四轮，已提交（标题「构建: SDK 公网发布准备」，未推送），代码审查零问题）：公网发布走 JitPack + Demo 三档开关。**
- 坐标改 `com.github.BLiYing.im-rtc-android:<模块名>:1.0.0`（`gradle.properties` 的 `IMRTC_GROUP`）：JitPack 多模块仓只按这个 group 提供文件，留 `com.imrtc` 的话 uikit POM 里对 engine 的传递依赖会拉不到。新增 `jitpack.yml`（JDK 17，只跑 `publishToMavenLocal`）；根 `build.gradle.kts` 在 `JITPACK=true` 时版本取它给的 `VERSION`（按提交号试水用），否则 `IMRTC_VERSION`。
- Demo 开关 `-PimrtcSdk=source|local|public`（或环境变量 `IMRTC_SDK`）：后两档 `settings.gradle.kts` **只 include `:demo`**，仓库分别加 `mavenLocal()` / `jitpack.io`（`content` 只放行自家 group）；Demo 只写 `call-uikit` + `call-engine-webrtc` 两行坐标（与 README / `/guide` 一致，engine 靠传递）。构建开头打印「Demo 用的 SDK：…」。
- 验过：`publishToMavenLocal` 出新 group 的三组包，uikit POM 依赖 `com.github.BLiYing.im-rtc-android:call-engine:1.0.0`；`-PimrtcSdk=local :demo:assembleDebug` 通过，依赖树全是坐标、`projects` 只剩 `:demo`；`public` 档解析到 jitpack 且 FAILED（还没推 tag，预期内）；非法取值直接报错；`VERSION` 环境变量不带 `JITPACK=true` 时不生效。`./scripts/test.sh` 6 步全绿。
- **JitPack 上的真实构建没验**：要推 tag（或先推提交用提交号试）后看 `https://jitpack.io/com/github/BLiYing/im-rtc-android/<版本>/build.log`；compileSdk 36 在 JitPack 镜像上能不能自动装平台是最大的未知。
- 上一轮（§A `73a30f2`、Maven 配置 `7cecd91`）已提交，§A 未上真端。

**2026-09-16（续）：三件事。①② 已提交 `9e2e476`，③ 已提交 `f091ab9` 且真机验收通过。**

**① 协议新字段 `call.incoming.inviter`（「谁邀请的你」，四端同步改，本仓这一份）。**
- 线路字段 `inviter`：首次邀请 = 主叫；`call.invite_more` 加进来的人 = 发那条加人请求的人。
  **空串回落到 `caller`**，回落做在 `CallStateMachineRecv.handleIncoming`，宿主永远拿得到一个非空的人。
- `IMFramesCall.INCOMING` 加字段；`IMCallEngineListener.onCallReceived` 加 `inviter`（放在 `caller` 之后，
  **直接改签名、不留旧重载**）；`IMEventDispatcher` 透传；`IMKitListener` 透传给 reducer 与宿主。
- Kit：`IMCallViewState.inviter` + 新增 `incomingFromUid`（`inviter` → 格子里第一个人 → `peer`）；
  来电横幅 `IMIncomingBanner` 与来电页 `IMCallView.renderAudio`（**仅 INCOMING 阶段**）都改用它。
  **摆格子、`state.caller`、选人页的 `callerUid` 一律没动。**
- Demo `DemoSession` 与 `JavaApiCheck` 同步改签名。
- 一致性向量：本仓代码不用改（`VectorMatch` 是递归子集比对，回调 args 多一个键不算错，只有**条数**必须对上）；
  主会话在 `call_fsm.json` 新增了两条 inviter 用例，本仓 `:call-engine:testDebugUnitTest` 带 `RTC_CONFORMANCE_DIR` 跑过。

**② 离场的发起人可以被重新邀请**（服务端去掉了 `invite_more` 对发起人的 `bad_params`，见 server current_task）：
`IMInvitePicker` 去掉「暂时无法邀请」分支（连带 `Row.Item.inCall`）；`IMCallViewReducer.incoming` 加 `selfUid`，
发起人就是自己时不给自己摆格子。注释跟改：`IMCallEngine.inviteMore`、`IMInviteContext.callerUid`、`IMCallViewState.caller`。

**验证**（①②，`test.sh` 全量没跑）：`:call-engine:testDebugUnitTest`（23 条，新增 2 条：带 inviter / 旧服务端回落）、
`:call-uikit:testDebugUnitTest`（`CallViewStateTest` 13 条，新增 1 条）、`:demo:compileDebugKotlin` —— BUILD SUCCESSFUL。

**③ 来电铃声 + 回铃音**（草图范围，不做振动/锁屏）：
- 素材 `call-uikit/src/main/res/raw/im_ringtone.mp3`（4s 循环）/ `im_ringback.mp3`（5s 周期，450Hz 1s 通 4s 断）。
- 纯判据 `IMRingRules.ringtoneFor(state, muted)`：muted / isMeeting 无条件不响，INCOMING→来电铃声，
  OUTGOING→回铃音，其余不响；单测 `IMRingRulesTest`（7 条）。
- 播放层 `IMRingPlayer`（薄包 MediaPlayer，`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，
  **不碰 `AudioManager.mode`**——那是 `IMAudioRouter` 的地盘）；焦点两代 API 同 `IMAudioRouter`，
  **2026-09-16 真机验收通过**；但**没记下机型与 Android 版本**（本仓 CLAUDE.md 要求写明），
  所以焦点两代 API 里 O+ / O- 具体走过哪一条并不确定，下次真机窗口补记一次。
- 挂载点 `IMCallKit.update()`：`state=next` 前先存 `previousPhase`，同步（不进 `main.post`）调
  `applyRingtone`，为的是抢在接听后 `IMAudioRouter.start()` 抢焦点前面把铃声焦点 abandon 掉，
  否则通话音会被系统 DUCK。`IMCallKit.stop()` 也 `ring?.stop()`。
- 配置 `IMCallKitConfig.incomingRingtone` / `ringbackTone`（`Uri?`，默认 null 用内置素材）/
  `ringtoneMuted`（默认 false），现读，同 `bannerFirst` 读法。Demo 设置页加「静音来电铃声」开关。
- `./scripts/test.sh` 全量跑过，六步全绿。**真机验收已通过**（2026-09-16）：1v1/群 来电铃声起停、
  拨出回铃音、接听/挂断/取消瞬间铃声立即停、接听后通话音量没有被 DUCK、会议房与 `onCallMissed`
  全程不响、静音开关生效。**蓝牙耳机场景仍未验**，连同机型/版本记录一起留到下次真机窗口。
跑法：`RTC_CONFORMANCE_DIR=../im-rtc-server/docs/conformance ./gradlew :call-engine:testDebugUnitTest ...`。

**同日已提交 `1ea2013`**：选人页列出全部成员、搜索框放大镜（`ic_im_magnifyingglass.xml`）。

**体量**：`IMCallEngine.kt` 599、`IMSignalConnection.kt` 600 已到硬顶，下次改这两个文件先想好拆哪块。

#### 下一步

00. ~~公网发布~~：2026-09-17 tag `1.0.0` 已推，JitPack 首次构建约 2 分钟成功（compileSdk 36 没出问题），`-PimrtcSdk=public :demo:assembleDebug` 依赖全从 jitpack.io、编过。下次发版：改 `IMRTC_VERSION` 与 `IMCallEngineVersion.VERSION` → 推 tag → 请求一次 pom 触发构建；失败要在 JitPack 页面删记录再重试（失败会被缓存）。
1. 用户真机自测（服务端先重启）：发起人挂断后被邀请回来能响铃、接听，来电横幅不出现自己的格子，
   且横幅 / 来电页显示的是**把你加进来的那个人**（群通话中途加邀时不是发起人）。自测过了跑 `./scripts/test.sh` 再提交。

**本端预览对齐（`startLocalPreview(): cid` + `attachLocalView(cid, view)`）没做，原因**：
现在的 `IMWebRTCAdapter` 里，本端预览用的是一个**固定常量** `PREVIEW_TRACK_ID`（不是每次生成的 cid），
与真正发布时 `IMLocalPublisher.publish()` 现生成的 cid（`local-$kind-$nowMs`）是两套不相干的 id、
在两个不同层（media 层 / engine 层）。要做成 Web/iOS 那种「`startLocalPreview()` 返回 cid，
之后一路 `attachLocalView(cid, ...)` 认到底、含发布后」，得把 cid 生成从 `IMLocalPublisher` 挪到
预览发起的更早时刻、让预览与发布共用同一个 cid，这会牵动 `IMMediaAdapter` 接口、
`IMWebRTCAdapter.attachLocalPreview` 的采集/挂载时序、以及 `IMCallKit.localPreviewView`/`wantsLocalPreview`
的整套权限门时序——是媒体层改动，而**媒体功能改了要真机验收**（CONVENTIONS §10），
这轮没有设备可用，贸然改时序风险太高。先不做，等有真机窗口再单独立项评估。

**真机验收（本轮是纯签名/命名改动，媒体与状态机逻辑未动，暂不需要重验；下一次真机窗口仍按下表走）**：
1. **M1**：group 通话下发 `chat_group_id`/`user_data`/`timeout_sec`，被叫 `onCallReceived` 与接通后 `onCallBegin` 真的带到；`call.join` 场景（frank 在另一台设备加入）`onCallBegin` 能拿到 caller/chatGroupId。
2. **M2**：`IMInvitePicker` 真机走一遍——300ms 防抖是不是真的等到停手才发请求、滚到底是否稳定触发下一页、`slow` 搜索词 10 秒后是不是真的转成失败态、`fail` 搜索词的重试按钮能不能把请求发出去。
3. **M8**：Demo 两台设备各登一个账号，A 发群通话，B 用 A 日志里的 call_id 在「加入进行中的群通话」里加入，确认不振铃直接接通；服务端配了邀请鉴权回调时验 1409 两句文案分得开。
4. 老批次真机项（forceEnd 断网/秒挂、后台重连节奏、1v1 视频细节）见 `current_task.archive.md` 与 server 仓「跨端待验」，本轮没有动它们。

**待办**：
- **`IMCallKit.notifyOutgoing` 没有带 `IMCallOptions` 的重载**（`IMCallKit.kt` 体量已经到顶，这次先省了）：宿主自己调 `engine.call(options)` 又想用 Kit 画拨出界面时，`notifyOutgoing` 目前带不出 chatGroupId，界面上加人入口会看不到候选——这类宿主目前的替代路径是 `placeCall(calleeIds, mediaType, options)` 直接经 Kit 拨出。
- 静默失败点清单：`../im-rtc-server/docs/ops/silent-failure/android.md`（逐条状态只在那里）。`ensureCapture` 缓存死 source 那条排在 server「下一步」第 2 条。
- `call-engine/build.gradle.kts` 找向量仍「逐级往上找」，会捡到上层旧克隆（web 已修同类问题）。
- `CLIENT_PARITY.md` 真机验完再改，验之前停 🟡。
- 本端预览对齐 cid（见上）——等真机窗口。

#### 已知坑 / 限制

**测试 / 工具**
- `SignalConnectionTest` 里 `scheduler.advance(N)` 超过 `2×pingSec`（30 s）会触发心跳超时重连、污染退避断言：压在 30 s 内，或显式 `transport.deliver(PONG, "")`。
- 仓根 `temp_verify.py` 是多会话共用的活文件，本仓自己的验证脚本单独建文件（如 `temp_verify_reconnect_pacing.py`），免得竞争写丢内容。
- 禁止 `org.json`（JVM 单测里是空壳桩）；`protocol/` 与 `statemachine/` 不许 import android.*（门禁守着）。

**真机 / 环境**
- **真机连不上服务端先看链路**：局域网 IP（PKD130 可用），或 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`（**Pixel 2 XL 只能走这条**，和 Mac 同 SSID 不同 AP、ARP 不到）。
  拔线 / `adb kill-server` / 重启后隧道就没了，症状像登录 bug（`Failed to connect to /127.0.0.1:8787`）。判据：手机 ping 得通 Mac 且 Mac 上 `arp -n <手机 IP>` 有表项 → 局域网可用。
- PKD130（ColorOS / Android 15）`pm revoke` 被挡（`SecurityException`）：测无摄像头权限只能去系统设置手动关，或开「USB 调试（安全设置）」。
- 日志回传只有 `-demo-login` 接收口，生产环境是空转的（Pixel 2 XL 上全链路验收过）。
- 七项界面按「默认已验」收口、没实机走过（09-08 拍板，发现问题再回头查）；其中「返回键收小窗」已被证伪（收小窗后远端视频回不来）。

**媒体 / 渲染**
- **iOS 发的永远是单层 H264**（stasel 152 没有 simulcast 工厂），SFU 降层只砸 Android——**排查「为什么只有 Android 糊」先想这条**，别往本仓编码参数上找。换包暂缓，见 `../im-rtc-ios/current_task.md`。
- `maxBitrateBps` 是 h 一层的数、不是上行总量（720p simulcast 要 l+m+h = 2.15Mbps），混用会让 `SimulcastRateAllocator` 给顶层 0 bps、开局只有 l 出包；已由 `simulcastUplinkBudgetBps` + `PeerConnection.setBitrate` 播种兜住。
- libwebrtc 锁 M150（`150.7871.01`），与 iOS M152 不齐可接受；H.264 要专门跨端实测。
- **2006 阈值「3」未校准、Kit 不接 2006**（`when` 没有 `else`）：见 server「已知坑」。
- **摄像头意图必须在进房前给 Engine**（`IMCallKit.syncCameraIntent`）；接通后才开的靠 `publishCameraIfMissing` 补发（多一条无害 `room.mute muted=false`）。
- **关摄像头停的是采集**：进房前关 = `stopLocalPreview`（已发布的不碰），通话中关 = `setMuted` → `setCapturePaused`。进房前切后台采集不停；通话中重开本端预览可能闪一下。
- 本端 track id 必须就是 cid（msid 第二段）；远端轨道按 track_id 认领、不能按 stream id；轨道 / 归属 / 渲染器到达顺序不定，统一在 `bindRemoteTracks` 判重换绑。
- 「人先进来、轨道后到」是常态：摆格子时做的动作（层上报、尺寸、订阅）要能在轨道到达时再做一遍（`invalidateReportedLayer`）。
- `SurfaceViewRenderer` 的 `init` / `setEnableHardwareScaler` / `setScalingType` 只能主线程调，而调度器会吞异常（症状只有全黑）→ 渲染相关一律走 `IMWebRTCAdapter.onMain`。
- `SurfaceView` 一息屏就没、`EglRenderer` 不重画上一帧：回前台某格纯黑 = 那个对端不发帧了，别查渲染器（iOS `CAMetalLayer` 留旧帧，同故障两端长得不一样）。
- 渲染器按 uid 整通复用（`IMCallKit.remoteViews`，每次新建会闪）；小窗叠在全屏画面上要 `setZOrderMediaOverlay(true)`。
- 离房要停媒体，判据是「媒体还有没有人要」（房间与通话都回 idle 才停）。

**UIKit / 平台**
- `IMGrid.dimensions` 默认 aspect 是 0.7 不是 0.5（按 0.5 算 9 人排成 2×5，与 iOS 3×3 对不上）。
- `GridLayout.spec` 不能带权重（正方形失效）；`GridLayout` 会把 `spec(UNDEFINED)` 改写成具体下标，往小改行列数前先把子视图 spec 退回 `UNDEFINED`（`IMCallGridView.apply`）或 `removeAllViews()`——不转屏也踩得到。
- 横排里的占位格高度必须写死 0（裸 `View` 的 `wrap_content` 在 `AT_MOST` 下吃满整高，把控制条顶到屏幕顶上）。
- 小窗吸角要等容器量出来（宽 0 时 `IMPipLayout.origin` 退化成 x=0）。
- 运行时权限只能从 Activity 请求 → `IMPermissionActivity`（透明、不入最近任务）；「问过没」记在 `im-rtc-kit` SharedPreferences。
- 前后台判定只认亲眼看见 started 过的界面（`IMForegroundState`）：SDK 是半路装上的，任何「按数量判前后台」都会错。
- `IMActivityTracker.foreground()` 拿不到通话页，通话中要 Context 一律用 `appContext`。
- 前台服务：通话中必须起（`IMCallForegroundService`）；类型不能降级；类型不能超出已授权权限（Android 14+ 否则在 `onStartCommand` 里循环崩），`start()` 先判 `IMForegroundTypes.granted`。
- 悬浮球默认走应用内浮层、不申请 `SYSTEM_ALERT_WINDOW`（CONVENTIONS §8）。
- `onDisconnected(code, willReconnect)`（2026-09-15 由 `(code, reason)` 改名改类型）：`willReconnect` 由 `IMSignalConnection` 当场裁决，`IMKitListener` 直接用 `!willReconnect` 判「已放弃」，不用再猜 4403。
- SDK 版本号两处：`gradle.properties` 的 `IMRTC_VERSION` 与 `call-engine/.../IMCallEngineVersion.kt`（五端统一 1.0.0，握手 `android/1.0.0`），不等时 `SdkVersionTest` 红；Demo `versionName` 读 `IMRTC_VERSION`。tag 名与它同号、不带 v。

#### 关联工程 / 常用命令

本机（2026-09-05 实测）：JDK 17（`/usr/libexec/java_home -v 17`）· SDK android-36 / build-tools 36.0.0 · `adb` 在 `~/Library/Android/sdk/platform-tools/`（不在 PATH）· Intel Mac（模拟器 x86_64、真机 arm64，两个 ABI 都要能出包）。
真机：**OPPO PKD130 / Android 15**（局域网直连）· **Google Pixel 2 XL / Android 11**（`903KPED2067148`，只能 `adb reverse`）。

- 五仓（本地同级）：server（协议契约，只读）· ios（**本仓的对照实现**）· web · desktop · **android**（本仓）。
- 起服务端联调：`cd ../im-rtc-server && ./scripts/dev.sh`（:8787 / UDP 7881）。
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次
  ./scripts/test.sh                # 唯一测试入口：门禁 ×3 + 向量可达 + assembleDebug + 纯 JVM 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./gradlew publishToMavenLocal && ./gradlew -PimrtcSdk=local :demo:installDebug   # Demo 改用本地包
  ./gradlew -PimrtcSdk=public :demo:installDebug                                    # Demo 改用 JitPack 上的包
  ```

## 2026-09-17 傍晚（/simplify 清理收口时移出活快照）：「当前焦点」里更早的块

> 原文照录，正文未改。

**2026-09-17 16:35：拨出中左上角「收进小窗」补上（`58cde02`，未推送，`test.sh` 6 步全绿，PKD130 / Android 15 真机验过）。**
- 根因：`IMCallViewState.canMinimize` 原先只认 CONNECTING / CONNECTED（旧理由「拨出中收起不知道怎么挂断」，球下红键加上后已不成立），iOS / Web 是「除来电页与结束画面都给」。
- 放开后真机暴露两处：① 点收起时通话页还在前台、宿主页没 resume，形态判定只能 hidden，拨出中没有每秒计时不会重判 → `IMActivityTracker.onHostResumed` 触发重挑形态；
  ② 球下红键点了没反应（UP 被父容器截走，**原先接通后也一样**）→ 按下落在挂断上时整串手势不拦。未接通时球上显示「…」。
- 真机：拨出中收起出球 → 点球展开 → 再收起点球下红键发出 `call.cancel`；拨出中收起后无人接听 → 自动展开结束画面。**视频通话拨出中收起、接通后球变视频缩略没点过**。

**2026-09-17 下午：清掉三条待办（已提交，`./scripts/test.sh` 6 步全绿；本端预览 cid 用户真机验过）。**
- `IMCallKit.notifyOutgoing(peers, mediaType, IMCallOptions)` 重载：宿主自己 `engine.call(options)` 时群号 / `userData` 也进界面。为腾体量把切后台停摄像头拆到 `IMBackgroundCamera`。
- 找向量不再逐级往上：`call-engine/build.gradle.kts` 的 `conformanceDir` 只认主检出 / `.claude/worktrees/<分支>` 两种布局（同 web `e58ec8e`），`RTC_CONFORMANCE_DIR` 相对路径按仓根解析；测试侧 `ConformanceVectors.locate()` 只认系统属性。
- **本端预览对齐 cid**：`IMCallEngine.startLocalPreview(): String` + `attachLocalView(cid, view)`（旧的 `startLocalPreview(view)` 留 `@Deprecated`）。cid 由 `IMLocalVideoCid` 在调用方线程当场发，发布视频沿用；媒体层只剩**一条**摄像头轨道（id = cid），删掉 `PREVIEW_TRACK_ID` 与 `IMPreviewIntent`（起停都回到 Engine 线程上，不再需要那个号）。`IMMediaAdapter` 接口改了：`startLocalPreview(cid)`、新增 `attachLocalView(cid, view)`。**用户真机验过**：拨出中见自己且接通不断、来电页 / 通话中开关摄像头（灯灭 / 亮）、翻转镜像、切后台回来、群通话关着进房后再开（**PKD130 / Android 15**）。code-review 两条（关预览与进房发布竞态时旧轨道没释放、没发布的预览 cid 对不上只打日志）已修：媒体层 `replaceVideoTrack` 换轨道并 dispose 旧的；这条修复走的是竞态路径，真机没专门造过。CLIENT_PARITY v1.40。

**2026-09-17：夜里逐项补了五件（已推送），早上 PKD130 / Android 15 真机补验，顺手修了一个真机才暴露的问题。** SDK 1.0.0 已公网发布（JitPack，MIT），这些进下一个版本。
- `c2c20db` 摄像头打不开 / 中途被抢走回报 2002，下次打开重起采集（`IMCameraEvents`，静默失败审计 android #1）。
  `24787c8` 真机发现 2002 走 `cameraBlocked` 会把按钮锁成「无权限」、整通开不回来 → 改为只关摄像头、按钮可点；**真机验过**：被抢后点开摄像头重出画面，对端恢复。
- `4a5c983` 收 `call.ringing` 抛 `onUserRinging`，群通话里别人加的人也摆占位格（协议批次，server `dd60ca0`）。**真机验过**：bob 加 carol → 手机上「呼叫中…」→ 拒接「已拒绝」约 2s 收掉。
- `a4c9fb0` 通话音频跟随系统：扬声器关着时耳机 / 蓝牙优先、监听插拔（`IMAudioRoutePolicy`）。真机只验了扬声器开关（type 2 ↔ 1），**没耳机 / 蓝牙，插拔未验**。
- `38941d3` 会议房超过一屏「还有 N 人未显示」+ 屏外报 none（M1）；`61d09c6` 打开网络质量图标，「对方网络不佳」只在 1v1。
- **09-17 下午用户自测通过**：发起人挂断后被邀请回来能响铃接听；来电横幅不出现自己的格子；横幅 / 来电页显示把你加进来的那个人（CLIENT_PARITY v1.42）。
- **体量**：`IMCallEngine.kt` 600、`IMWebRTCAdapter.kt` 599、`IMCallView.kt` 599、`IMSignalConnection.kt` 600 已到硬顶，下次改先拆；`IMCallKit.kt` 拆后约 580。


## 2026-09-19（快照整理时移出活快照）：09-18 的「当前焦点」与旧「下一步」

> 原文照录，正文未改。

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

0. **还没验的**：视频通话拨出中收起、接通后球变视频缩略没点过；1v1 视频默认走听筒（改前就这样，要不要默认扬声器待定）。
1. **真机窗口清单**：
   - 铃声：蓝牙耳机场景 + **补记机型与 Android 版本**（O+ / O- 焦点 API 走的哪条）。
   - 老批次（forceEnd 断网 / 秒挂、后台重连节奏、1v1 视频细节）：archive「2026-09-15：forceEnd …真机验收清单」。
2. 2.0.0 调用结果改造：真机验（见当前焦点）→ 用户通知后发版。
3. 待办：静默失败清单 `../im-rtc-server/docs/ops/silent-failure/android.md`。

