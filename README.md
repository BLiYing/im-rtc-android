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

## 权限：宿主要自己申请的只有一条

**麦克风与摄像头不用宿主管。** `IMCallKit` 在三个闸口自己申请（发起通话 / 开摄像头 / 接听），
走的是三段式：说明卡 → 系统框 → 拒一次再劝 → 第二次才给「去设置」。弹框由一个透明的
`IMPermissionActivity` 负责，所以**拨出前 Kit 不在前台也能问**。
权限与前台服务类型都在 `call-engine-webrtc` 的清单里声明，靠 manifest merger 合进宿主，
**宿主一行都不用加**。

**别抢在前面替它申请。** 进 App 就问权限是拒绝率最高的问法，而且会**烧掉「第一次」**：
用户在那里拒绝之后 `shouldShowRequestPermissionRationale` 的状态就变了，真打电话时
Kit 的说明卡被跳过，直接落到「永久拒绝 → 去设置」那一屏。更要紧的是，被拒的**处置有业务语义**
——麦克风被拒＝取消整通话，摄像头被拒＝降级语音继续——只有 Kit 知道这次通话要哪些设备
（`IMPermissionGate.devicesFor`），宿主替它问，就把这个判断丢了。

**唯一留给宿主的是 `POST_NOTIFICATIONS`**（Android 13+）：

```kotlin
// 通话中会起前台服务，没有这条权限用户看不见「通话中」那条通知。
// 清单里已经替你声明了，缺的只是运行时这一次询问——**挑个与通话无关的时机问**，
// 比如设置页或首次进入某个功能，别放在通话链路上。
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
}
```

不给也不会影响通话本身——只是「通话中」那条通知不显示。

## 升级须知：`device_id` 现在是构造时校验（**破坏性变更**）

`IMCallEngine.Config` 的构造函数会校验 `device_id`，不合规**当场抛
`IllegalArgumentException`**（协议 §2.5：非空、≤64 **字节**、charset `[A-Za-z0-9_-]`）。

**这对宿主是破坏性的**：升级前传一个带空格的 `device_id`（最常见的是直接用 `Build.MODEL`）
只是登录失败，升级后是**构造 `Config` 那一行就崩**。

**校验本身是有意保留的**，因为原先的表现更糟：服务端回 `1004`，客户端无限退避重连，
界面上只写着「登录失败」，而服务端那句说得很清楚的「`device_id` 只允许 `[A-Za-z0-9_-]`，
出现了 `' '`」**到不了端上**。真机上踩过一次，查了一轮才定位到是机型名。
崩在构造那一行，至少一眼能看出是什么。

**升级前请先清洗 `device_id`**，两条现成的路：

```kotlin
// 一、只想先自检：这个方法是公开的（@JvmStatic，Java 也能调），不合规同样抛异常。
IMCallEngine.Config.checkDeviceId(candidate)
```

```kotlin
// 二、推荐的取法：随机 id 存一次，之后每次读同一个。
//     device_id 要求**跨重启稳定**，SDK 只校验不改写——悄悄替宿主改掉，
//     宿主自己那套设备管理（设备列表、注销设备、顶号）就跟服务端对不上账了。
private fun deviceId(context: Context): String {
    val prefs = context.getSharedPreferences("im-rtc", Context.MODE_PRIVATE)
    prefs.getString("device_id", null)?.let { return it }
    val model = Build.MODEL.map { ch ->
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-') ch else '-'
    }.joinToString("")
    val id = model.take(24) + "-" + UUID.randomUUID().toString().take(8)
    prefs.edit().putString("device_id", id).apply()
    return id
}
```

两个坑：

- **别用「删掉非法字符」那种清洗**：`MI 8` 与 `MI8` 是两款不同的机器，删完撞成同一个
  `device_id`，而撞号的后果是两台设备互相顶号、轮流把对方踢下线。**换成 `-` 才不会。**
- **别拿 `Build.MODEL` 本身当 `device_id`**：同一账号下两台同型号手机会撞号，
  症状同上。上面那段加了随机后缀就是为了这个。

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
