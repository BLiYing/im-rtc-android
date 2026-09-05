# Current Task — im-rtc-android（Kotlin Engine + UIKit + Demo）

> **活快照**：只记当前状态，**就地覆盖、不追加**。历史见 `git log`。
> 工程规范见 [CONVENTIONS.md](CONVENTIONS.md)；方案与分期见 `im-rtc-server` 的
> `docs/design/RTC_CALL_DESIGN.md` §10；界面以草图 `docs/design/sketches/RTC_CALL_UX_SKETCH.html` §02~§05 为准。

## 当前焦点

**仓库刚建（2026-09-05），只有文档与体量门禁，零代码。**

两条已拍板的决定：
1. **Android 进入产品范围**，四仓变五仓。设计文档 §1 #6 原文「Android 暂不考虑」与
   §8「若进来再评估共享 C++ 核心」**尚未同步修改**，改动待办见「下一步」第 0 项。
2. **Kotlin 独立实现，不共享桌面端的 C++ 核心。** 理由：Android 的 libwebrtc 绑定本就是 Java，
   走 C++ 核心仍要写一整层 Java 媒体适配器，能共享的只有协议 + 状态机 + 信令约 3k 行，
   代价却是 NDK + 四个 ABI + JNI 生命周期。与设计文档否掉 Rust/KMP 是同一条理由。

**开工闸门（两条都满足才动第一刀）**：
- **iOS 媒体真机验收通过**——现在 iOS 的媒体只证明了「编得过」，一行都没在真机上跑过。
  未验证的媒体设计不该同时复制到第五个实现里。
- **设计文档 §7.5 回调表冻结**——iOS 落地这一周里它还在加 `updateToken`、`setSpeakerOn`、
  `onConnected`，每一条都是「写第三个实现时才发现前两个漏了」。现在开工，补表成本从 ×4 变 ×6。

**在此之前本仓只做一件事：接收契约。** 从 Kotlin / Java 视角评审
`../im-rtc-server/docs/RTC_PROTOCOL.md` 与 `docs/conformance/*.json`，
发现「Kotlin 侧别扭 / Java 宿主调不了」的地方**现在就回 server 仓提**，别等开工。

## 下一步

0. **五仓文档同步**（在 server 仓做）：设计文档 §0 仓库表、§1 #6、§7 标题「三端同构」、
   §8、§10 分期，四个老仓的「关联仓库/关联工程」段落，以及被引用了三次却一个都不存在的
   `CLIENT_PARITY.md`——五端起它必须是逐端逐特性状态的唯一真相源。
1. **等闸门**（见上）。期间只评审协议。
2. **第一刀**：Gradle 骨架（`call-engine` / `call-engine-webrtc` / `call-uikit` / `demo`
   四模块 + 版本目录）+ `scripts/test.sh` + **JVM 单测跑通五份一致性向量的 runner**。
   不需要 org.webrtc、不需要设备。
3. **第二刀**：`protocol/` + `statemachine/`——对照 `../im-rtc-ios/Sources/IMCallEngine/`
   的同名两层，向量全过。仍然不需要设备。
4. **第三刀**：`signaling/`（OkHttp WebSocket、握手、心跳、req_id 配对、退避重连、4401 三次上限）
   + 门面与回调表 + 日志回传。验收：真连本地服务端跑通进房离房（对齐 iOS 的 `LiveServerTests`）。
5. **第四刀**：`call-engine-webrtc` 媒体 + 前台服务 + 音频焦点与路由。**真机验收**，
   且要与 Web、iOS 各互打一次。
6. **第五刀**：`call-uikit`（来电横幅 / 1v1 四态 / 九宫格 / 悬浮球）+ Demo 三屏。

## 已知坑 / 限制

**三个开工前问题已定（2026-09-05）**
- **libwebrtc 里程碑对不齐，接受**：iOS 是 M152（`stasel/WebRTC` 152.0.0），Android 侧
  `io.github.webrtc-sdk:android` **没有 M152**，最新是 M150（`150.7871.01`），另有仍在打补丁的
  M144 稳定线。**锁 M150，兜底 M144**（改版本目录一行的事）。防线不是版本对齐，而是
  协议 + 向量 + 跨端互打，见 `../im-rtc-server/docs/CLIENT_PARITY.md` §3。**H.264 要专门跨端实测。**
- **UI 用原生 View，不用 Compose**：`SurfaceViewRenderer` 本就是 View；UIKit 是要塞进别人 App 的库，
  不该把 Compose 运行时强加给宿主。宿主自己是 Compose 应用不受影响（`AndroidView` 能嵌）。
- **不新建 Android 宿主空项目**：空壳证明不了任何东西。真实校验场是
  `demo/` 里的 **`JavaApiCheck.java`**（纯 Java 调一遍全部公开 API，**编译即验证**）——
  这一招在 iOS 侧抓到过真问题。等公司真有 Android App 要接入时，再按 P5 的方式做一次接入示例。

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

**Android 侧特有的（详见 [CONVENTIONS.md](CONVENTIONS.md) §8）**
- 通话中必须起前台服务（Android 14 起还要 `FOREGROUND_SERVICE_MICROPHONE` / `_CAMERA`）。
- 通知要 `POST_NOTIFICATIONS`（Android 13+），没有它用户看不见「通话中」。
- 音频路由分两代 API（API 31 前后），两条路都要写；不设 `MODE_IN_COMMUNICATION` 就没有回声消除。
- 悬浮球默认走应用内浮层，**不默认申请 `SYSTEM_ALERT_WINDOW`**（敏感权限，影响宿主上架）。
- `PeerConnection.Observer` 跑在 signaling 线程上，禁止阻塞、禁止直接碰 UI。
- **禁止 `org.json`**：它在 JVM 单测里是空壳桩，一律返回默认值，测试会假绿。
- 厂商 ROM 的后台限制差异很大，**「我这台过了」不等于「Android 过了」**。

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
- 常用命令（脚本随第一刀落地）：
  ```bash
  ./scripts/install-hooks.sh       # 新 clone 跑一次，装 pre-commit 体量门禁
  ./scripts/test.sh                # 唯一测试入口：体量门禁 + 编译 + 单测
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/check-file-size.sh --selftest   # 门禁自检
  ```
