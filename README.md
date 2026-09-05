# im-rtc-android

`im-rtc` 音视频产品的 **Android 客户端 SDK**，纯 **Kotlin**，最低 **Android 7.0（minSdk 24）**。

| 产物 | 是什么 |
|---|---|
| **`com.imrtc:call-engine`** | **无 UI** 核心：信令 / 状态机 / 设备，能力通过**回调**暴露；不依赖 libwebrtc |
| **`com.imrtc:call-engine-webrtc`** | 媒体实现（`org.webrtc`），以 `MediaAdapter` 接口接进 Engine |
| **`com.imrtc:call-uikit`** | **整套通话 UI**：来电页与横幅、1v1 四态、群通话九宫格、悬浮窗 |
| **Demo App** | 登录 / 拨号 / 通话记录 / 设置，两种集成方式各跑一遍 |

## 两种集成方式

- **只引 Engine**：拿回调，界面自己画。适合已有设计体系的 App。
- **Engine + UIKit**：整套 UI 直接用，可换图标/配色/文案，不改流程。

**UIKit 不是特权组件**——它只消费公开回调表，没有私有通道。

## 为什么是 Kotlin 独立实现，而不是共享桌面端的 C++ 核心

**2026-09-05 拍板。** Android 官方 libwebrtc 绑定本来就是 Java（`org.webrtc`）：
`PeerConnectionFactory`、`SurfaceViewRenderer`、`JavaAudioDeviceModule` 全在 Java 侧。
走 C++ 共享核心仍然要写一整层 Java 媒体适配器，**能共享的只有协议 + 状态机 + 信令约三千行**，
代价却是 NDK、四个 ABI、以及 JNI 生命周期（对象先死、回调后到）。
这与设计文档否掉 Rust/KMP 的理由是同一条：**为共享三千行逻辑引入跨语言构建，是净负担。**

五端**不共享代码，共享「协议 + 状态机 + 一致性测试向量」**，靠
`im-rtc-server/docs/conformance/*.json` 钉死行为一致。

## 边界

**不做宿主业务界面**（消息气泡、会话列表、群横幅）。Demo 的通话记录页是**示范**，不是要求。

## 文档

| 文档 | 内容 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 项目说明、结构、工作流程与「完成的定义」 |
| [CONVENTIONS.md](CONVENTIONS.md) | 工程规范（分层 / 体量 / **Java 互操作** / 协程 / **Android 平台约束** / 测试） |
| [current_task.md](current_task.md) | 当前进度活快照 |
| 协议契约 | 在 [im-rtc-server](https://github.com/BLiYing/im-rtc-server) 的 `docs/RTC_PROTOCOL.md`，本仓只读引用 |

## 开发

```bash
./scripts/install-hooks.sh   # 新 clone 跑一次
./scripts/test.sh            # 体量门禁 + 编译 + 单测（骨架落地后可用）
```

**音视频一律真机验收**：模拟器没有真摄像头、音频链路也不作数。

## 状态

**任务一到任务五全部落地（2026-09-05）**：Gradle 四模块、四道门禁、协议层、三台状态机、
信令与门面、libwebrtc 媒体、通话 UI 与 Demo 三屏（**界面已按 iOS Demo 对齐**：
拨号页四块卡 + 底部 tab + 群呼选人多选 + 通话记录列表 + 画质档位）。
`./scripts/test.sh` 六步全绿（60 个用例，纯 JVM，不需要设备），
并已在真机上跑通「免密登录 → 握手 → 建会议房 → 进房 → 媒体拉起 → 界面计时」。

**媒体是通的**（2026-09-05 复核）：服务端收到过 Android 的上行 Track，真机 logcat 里
pub 与 sub 的 ICE 都到 CONNECTED，通话记录里有来自 iOS Demo 的来电。
（此前 README 写过「UDP 过不去」，那是判错了：`-ice-loopback` 只是**多**宣告一个
127.0.0.1，LAN 候选一直都在；`adb reverse` 也只影响信令，不影响 ICE 自己谈出来的媒体路径。）
开工闸门是「iOS 媒体真机验收通过 + 设计文档 §7.5 回调表冻结」——
在此之前本仓的价值是**接收契约**：从 Kotlin / Java 视角评审协议，发现问题回 server 仓提。
