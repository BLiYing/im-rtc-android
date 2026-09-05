# im-rtc-android — 项目说明（供 Claude 读取）

## 项目简介
`im-rtc` 音视频产品的 **Android 客户端 SDK**，纯 **Kotlin**。交付四样东西：

| 产物 | 是什么 | 谁用 |
|---|---|---|
| **`call-engine`** | **无 UI** 核心：信令、通话状态机、设备控制，全部能力通过**回调**暴露。**不依赖 libwebrtc** | 想自己画 UI 的宿主 |
| **`call-engine-webrtc`** | 媒体实现（`org.webrtc`），以 `IMMediaAdapter` 接口接进 Engine | 需要真通话的宿主都要引 |
| **`call-uikit`** | **整套通话 UI**：来电页/横幅、1v1 四态、群通话九宫格、悬浮窗 | 想一天内上线通话的宿主 |
| **Demo App** | 登录 / 拨号 / 通话记录 / 设置，两种集成方式各跑一遍 | 验证「只用公开回调就能做出完整体验」 |

**边界（重要）**：本仓**不做宿主业务界面**——消息气泡、会话列表、"群里谁在通话"的横幅，
都由宿主拿 Engine 回调自己实现。Demo 里的「通话记录页」是**示范**，不是要求宿主照抄。
详见 `im-rtc-server` 的 `docs/design/RTC_CALL_DESIGN.md` §9。

**UIKit 不是特权组件**：它只消费公开回调表，没有任何私有通道。一旦某个界面需要 Engine 开私有口子，
说明回调表少了一项 —— **补表，不开后门**。这是「自画 UI 与 UIKit 能力对等」的唯一保证。

**跨平台策略（已定，别再翻案）**：五端**不共享代码，共享「协议 + 状态机 + 测试向量」**。
Android 用 Kotlin **独立实现**（2026-09-05 拍板），不共享桌面端的 C++ 核心——
理由见 [README](README.md)：能共享的只有约三千行纯逻辑，代价是 NDK + 四个 ABI + JNI 生命周期。

## 技术栈
- 语言：**Kotlin**，JDK 17，**minSdk 24 / compileSdk 35**（开工时按当时最新核定）
- 构建：**Gradle KTS + 版本目录 `gradle/libs.versions.toml`**（依赖版本集中一处锁定）
- 媒体：**libwebrtc 预编译包 `io.github.webrtc-sdk:android`，锁 `150.7871.01`（M150）**，
  兜底 `144.7559.15`（M144，补丁最多的成熟线）。`PeerConnectionFactory` / `SurfaceViewRenderer` /
  `JavaAudioDeviceModule`。**与 iOS 的 M152 对不齐是已知且可接受的**——理由与防线见
  `../im-rtc-server/docs/CLIENT_PARITY.md` §3
- 信令：**OkHttp `WebSocket`**，JSON
- JSON：**自研严格值模型**（对齐 iOS 的 `IMJSON`：类型里压根没有 null 与 double 两个 case）。
  **禁止 `org.json`**——它在 JVM 单测里是空壳桩，方法一律返回默认值，测试会假绿
- UI：**原生 View + ViewBinding**（2026-09-05 定，不用 Compose：视频渲染的
  `SurfaceViewRenderer` 本就是 View，Compose 里还得 `AndroidView` 包一层；
  UIKit 是要塞进别人 App 的库，不该把 Compose 运行时强加给宿主），**不引第三方 UI 库**

## 工程结构（规划，落地时按此展开）
```
im-rtc-android/
├── settings.gradle.kts / build.gradle.kts
├── gradle/libs.versions.toml           # 依赖与版本，唯一真相源
├── call-engine/                        # 无 UI，且不依赖 org.webrtc
│   ├── src/main/kotlin/com/imrtc/engine/
│   │   ├── IMCallEngine.kt             # 门面：login/call/accept/hangup/joinRoom…
│   │   ├── IMCallEngineListener.kt     # 回调表（对应设计文档 §7.5 回调总表）
│   │   ├── protocol/                   # 值模型、信封、帧注册表与字段声明、错误码、reason
│   │   ├── statemachine/               # 通话机 / 房间机 / Engine 总状态（纯逻辑、跑一致性向量）
│   │   ├── signaling/                  # WS 客户端、握手、心跳、按 req_id 配对、退避重连
│   │   ├── media/                      # IMMediaAdapter 接口（只有接缝，没有实现）
│   │   ├── device/                     # 麦克风/摄像头/扬声器、蓝牙路由、权限
│   │   └── log/                        # IMRTCLog + 脱敏 + 可注入 sink
│   └── src/test/kotlin/                # 纯 JVM 单测：向量、状态机、编解码（不需要设备）
├── call-engine-webrtc/                 # 媒体实现（org.webrtc）+ 前台服务 + 音频路由
├── call-uikit/                         # 来电横幅 / 1v1 / 九宫格 / 悬浮窗 / 控制条
├── demo/                               # Demo App，含 JavaApiCheck.java（Java 互操作编译即验证）
└── scripts/                            # 门禁与测试入口
```

## 工作约定
- **每次开始主要回复前，先读 `current_task.md` 恢复上下文**，改动后更新它。
- **`current_task.md` 是「活快照」不是流水账**：固定四节，**就地覆盖、禁止追加 Status 块**。
- **工程规范见 [CONVENTIONS.md](CONVENTIONS.md)**（分层 / 体量 / Java 互操作 / 协程 / 日志 / Android 平台约束 / 测试）。
- **协议契约在 `im-rtc-server/docs/RTC_PROTOCOL.md`，本仓只读引用**，不得单方面加字段。
  改协议 = 改五个仓 + 同步一致性向量。
- **单文件体量红线**：非测试 `.kt` / `.java` **> 600 行**要按职责拆分。
  硬闸：`scripts/check-file-size.sh`（pre-commit + `test.sh` 第 1 步）。新 clone 跑 `./scripts/install-hooks.sh`。
- 文档引用代码**不写行号**，写文件路径 + 符号名：`call-engine-webrtc/.../IMWebRTCAdapter.kt` 的 `attachView()`。

## 工作流程与「完成的定义」
动手前（Read，不靠记忆）：
- 改代码前先 Read [CONVENTIONS.md](CONVENTIONS.md)；涉及协议字段再 Read `../im-rtc-server/docs/RTC_PROTOCOL.md`。
- 加/改**公开 API** 前，先 Read 设计文档 §7.5 回调总表——**回调名五端同名**。
- **写某一层之前先读 iOS 对应那一层**（`../im-rtc-ios/Sources/IMCallEngine/`）：
  它是同形态的另一份实现，坑已经踩过一轮。**是对照，不是照抄**——Kotlin 有自己的惯用法。

声明「完成」前必须全部满足，并在回复中**贴出 `./scripts/test.sh` 的输出**：
1. 新功能配套单测，由测试目标自动纳入。
2. `./scripts/test.sh` 全绿（体量门禁 + 编译 + 单测）。
3. 更新 `current_task.md`；里程碑完成同步更新 server 仓设计文档 §10 的状态与日期（YYYY-MM-DD）。
4. 明确说清楚「没做什么 / 已知限制 / TODO」，不假装完成。
5. **音视频功能一律真机验收**：模拟器没有真摄像头、音频链路不作数。编译通过 ≠ 功能可用。
6. **说清楚在哪个 Android 版本、哪台机器上验的**：音频路由、前台服务、后台限制、悬浮窗权限
   在各版本与各厂商 ROM 上差异很大，"我这台过了"不等于"Android 过了"。

主动建议（不必用户开口）：
- 完成较大功能后建议跑 `/code-review` 自审。
- 触及 token / 权限 / 媒体密钥时建议跑 `/security-review`。

## 构建 / 测试
```bash
./scripts/install-hooks.sh       # 新 clone 跑一次
./scripts/test.sh                # 唯一测试入口：体量门禁 + 编译 + 单测
BUILD_ONLY=1 ./scripts/test.sh   # 只编译
```
> 脚本与 Gradle 工程随第一刀落地补齐；**当前仓库只有文档与体量门禁**。

## 关联仓库
| 仓库 | 内容 |
|---|---|
| [im-rtc-server](https://github.com/BLiYing/im-rtc-server) | 控制面 + SFU + **协议契约**（本仓只读引用） |
| [im-rtc-ios](https://github.com/BLiYing/im-rtc-ios) | Engine + Kit + Demo（Swift）——**本仓的对照实现** |
| [im-rtc-web](https://github.com/BLiYing/im-rtc-web) | engine + uikit + Demo（TS/React） |
| [im-rtc-desktop](https://github.com/BLiYing/im-rtc-desktop) | C++17 Engine + Qt Demo |
| **im-rtc-android**（本仓） | Engine + UIKit + Demo（Kotlin） |

**本仓排在 iOS 之后**，开工闸门是「iOS 媒体真机验收通过 + §7.5 回调表冻结」。
在此之前它的价值是**接收契约**：协议与一致性向量必须能在 Kotlin / Java 侧原样实现
（例如帧结构不得依赖 JS/Swift 特有的数据表达）。**现在就提，别等开工。**
