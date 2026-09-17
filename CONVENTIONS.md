# CONVENTIONS —— im-rtc-android 工程规范（Kotlin）

> 本文是**本仓代码的硬约束**。`CLAUDE.md` 讲「这个项目是什么」，本文讲「代码必须长什么样」。
> 协议字段与回调命名以 `im-rtc-server/docs/RTC_PROTOCOL.md` 与设计文档 §7.5 为准，本文不重复。

## 1. 分层与模块划分

```
call-engine          无 UI。**不依赖 org.webrtc**，也不 import android.view/widget。
call-engine-webrtc   媒体实现。依赖 org.webrtc + call-engine，实现 IMMediaAdapter。
call-uikit           UI。依赖 call-engine，只通过公开回调获取信息。
demo                 示例 App。依赖三者，不含任何 SDK 逻辑。
```

**依赖方向单向**：`demo → call-uikit → call-engine`，`call-engine-webrtc → call-engine`。
**call-engine 绝不反向依赖任何一个。**
Engine 内部：`IMCallEngine（门面）→ protocol / statemachine / signaling / media / device`，
子模块之间通过接口解耦，不互相 import 具体类型。

**`call-engine` 为什么不许依赖 org.webrtc**（与 iOS 同一条约束，理由也一样）：
libwebrtc 是几十 MB 的预编译包，一旦被 Engine 直接依赖，「跑一次单测」就变成
「起模拟器 / 连真机 + 拉几十 MB」。媒体只以 `IMMediaAdapter` 接口出现，真实现放隔壁模块。

**更强的一条：`protocol/` 与 `statemachine/` 两个包不得 import 任何 `android.*`。**
它们必须是纯 JVM 代码，用普通 JUnit 就能跑，**不需要 Robolectric、不需要设备**。
需要平台能力（时间、随机数、调度）就注入抽象，别直接摸系统 API。

**新增东西放哪**：
| 新增 | 放哪 | 不要放哪 |
|---|---|---|
| 一个新回调 | `IMCallEngineListener.kt` + 设计文档 §7.5 同步 | 临时加个 lambda 属性 |
| 一个新信令帧 | `protocol/frames/` + 帧注册表 + 状态机对应分支 | 在 WebSocket 回调里就地解析 |
| 一个新界面 | `call-uikit/<场景>/` 独立文件 | 往已有 Activity/Fragment 里塞 |
| 媒体能力 | `call-engine-webrtc/`，经 `IMMediaAdapter` 接口暴露 | UIKit 里直接调 org.webrtc |

**`call-uikit` 禁止直接 import `org.webrtc`**。画面通过 Engine 提供的
`attachView(uid, view)` 挂载，换媒体实现时 UIKit 一行不用改。

## 2. 文件体量红线（防「上帝类」）

- 非测试 `.kt` / `.java` 文件 **> 600 行**即失败。
- 硬闸：`scripts/check-file-size.sh` —— pre-commit + `scripts/test.sh` 第 1 步。
- **超标的正确处理是拆分，不是放宽阈值**：
  - Activity/Fragment 膨胀 → 抽**协作对象**（`XxxPresenter` / `XxxController` / `XxxLayouter`），
    不是堆一堆私有方法充数。
  - 一个类型的不同关注点 → 拆成多个文件（`CallStateMachine.kt` / `CallStateMachine+Recv.kt` 风格）。
  - 状态机膨胀 → 按状态族拆文件。
- 函数层面：**单个函数超过 ~50 行**就该拆；`when` 的每个分支各自成函数。
- 姊妹项目 IMProgram 的教训：两个界面类长到 3000+ 行后极难改动，最终被迫大拆。**别重蹈覆辙。**

## 3. 命名

- 类型 `UpperCamelCase`，方法/属性 `lowerCamelCase`，常量 `UPPER_SNAKE`。
- **公开类型统一 `IM` 前缀**（`IMCallEngine`、`IMCallParams`），避免与宿主符号冲突；
  包名统一 `com.imrtc.*`。
- **回调名五端同名**：`onCallReceived` / `onCallBegin` / `onCallEnd` …（见设计文档 §7.5）。
  **语义与参数名必须与 §7.5 对齐**，不许「Android 上叫得顺口一点」。
- 固定缩写按 Kotlin 惯例写成词：`Sdp` `Ice` `Rtp` `Uid`；常量里才全大写。
- 时间量带单位：`timeoutMs`、`durationSec`。
- 禁止 `data` / `info` / `manager` / `helper` 这类无语义名字做类型名。
- 格式化交给 `.editorconfig` + ktlint（**入库、门禁校验**），不靠人手对齐。

## 4. Java 互操作（宿主可能是 Java，公开面必须 Java 可用）

- **Kotlin 的默认参数对 Java 不可见** → 公开的构造器与方法加 `@JvmOverloads`。
- **`suspend` 函数 Java 用不了** → **公开 API 一律不用 `suspend`**，用「方法 + 回调接口」，
  与 §7.5 的形态一致；协程只在内部用。
- 公开面**不出现 Kotlin 独有类型**：`Result`、value class、带泛型的 sealed、
  解构用的 `Pair/Triple`、`Nothing`。用普通类 + getter，枚举用 `enum class`。
- companion 里的公开常量/工厂加 `@JvmStatic` / `@JvmField`。
- 可空性显式：Kotlin 的 `?` 会生成 `@Nullable`，**公开面别用平台类型**（来自 Java 的 `!`）。
- **回调同时提供接口与 lambda 两种注册形式**（宿主两种习惯都有）。
- **新增公开 API 后要往 `demo/.../JavaApiCheck.java` 里补一行调用**——编译即验证。
  这一招在 iOS 侧抓到过真问题（生成的选择器难用到宿主要写怪语法），Kotlin→Java 同样会踩：
  默认参数、`suspend`、value class 都是「Kotlin 侧看着好、Java 侧根本调不了」。
- **Engine 内部不受此约束**：只有跨模块的公开面要 Java 友好，内部尽管用 Kotlin 的表达力。

## 5. 协程与线程

- **主线程只做 UI**。信令、状态机、媒体回调各自在自己的调度器上跑，
  **回调给宿主前显式切主线程**（宿主拿到回调就画界面，别让它们自己 hop）。
- Engine 持有自己的 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，
  **`logout()` 必须 cancel 它**：重连、心跳、统计上报都要停。
- **禁止 `GlobalScope`**（生命周期不可控，泄漏常客）。
- **禁止在主线程 `runBlocking`**。
- 共享可变状态必须有明确归属：单线程调度器 / `Mutex` / 不可变快照，三选一，**并在声明处写注释说明**。
- 不在锁内做 IO、不在锁内回调外部（宿主代码可能重入）。
- **`PeerConnection.Observer` 的回调跑在 WebRTC 的 signaling 线程上**：
  禁止在里面阻塞、禁止直接碰 UI、禁止再同步回调进状态机——一律往自己的队列里投递。
- **媒体热路径（每帧/每包）禁止分配大对象、禁止写文件、禁止等锁**；
  要通知上层就往有界队列里丢，**队列满了丢弃而不是阻塞**。

## 6. 日志

- **统一走 Engine 的日志入口**（`IMRTCLog`，可注入 sink），UIKit 与 Demo 共用，前缀区分
  `[Engine]` / `[Kit]`。
- **禁止 `android.util.Log` / `println` 直接出现在业务代码**。
  （别指望 R8 帮你去掉：不显式配 `assumenosideeffects`，`Log.d` 会原样留在包里，
  参数字符串照样拼。）
- 必带字段：`callId` / `roomId` / `uid`（有哪个带哪个）。
- **脱敏**：token 类凭据、完整 SDP 不整条打印；凭据只打前 6 位 + 长度。
- **媒体回调里禁止日志**（每帧/每包都走的路径）。
- 日志回传（对齐另外三端，落到 `im-rtc-server/dev-logs/`）**必须给请求设超时**：
  一个卡住的请求能让后面所有日志静默丢掉——iOS 上踩过，症状是日志停在某个时间点而应用还活着。

## 7. 内存与生命周期

- **只持 `applicationContext`**。公开 API 收到的 `Context` 一律 `.applicationContext` 后再存；
  持有 Activity / View 一律弱引用。**这是 Android 端头号泄漏源。**
- 监听器集合用 `CopyOnWriteArrayList`，**注册必须有对应的注销**；
  Activity/Fragment 侧在 `onDestroy` 注销，别指望 GC。
- **`SurfaceViewRenderer` 的 `init()` / `release()` 必须成对**，且释放顺序有讲究
  （先解绑轨道再 release，反了会崩在 native 层）。
- `EglBase` 的共享上下文全进程只建一份，工厂与所有渲染器共用。
- `PeerConnectionFactory.initialize()` 每进程只能调一次。
- **Engine 必须能被完整释放**：写一个「反复 login/logout 100 次不涨内存、线程数不涨」的测试钉死。
- 视频视图挂载/卸载要成对；`attachView` 之后宿主释放视图时 Engine 必须能感知（弱引用视图）。

## 8. Android 平台约束（这一节是 Android 独有的坑）

- **通话中必须起前台服务**，否则进程随时被系统回收，表现是「切后台一会儿就掉线」。
  `foregroundServiceType` 取 `microphone`（视频再加 `camera`），**Android 14 起还要声明对应的
  `FOREGROUND_SERVICE_MICROPHONE` / `_CAMERA` 权限**。用 `phoneCall` 类型的前提是接
  Telecom / `ConnectionService`——属后续期，MVP 不做。
- **权限是运行时的**：`RECORD_AUDIO` / `CAMERA`，**Android 13 起通知还要 `POST_NOTIFICATIONS`**
  （没有它前台服务的通知不显示，用户看不见通话中）。权限被拒的路径必须有明确回调，不许静默失败。
- **音频路由分两代 API**：Android 12（API 31）起用 `AudioManager.setCommunicationDevice()`；
  之下只能用已废弃的 `setSpeakerphoneOn()` / `startBluetoothSco()`。两条路都要写，**并说清楚在哪个
  版本上验过**。
- **必须申请音频焦点**（`AudioFocusRequest`）并设 `MODE_IN_COMMUNICATION`：不设模式就没有硬件回声消除，
  自己会听到自己——而那听起来像「对方设备有问题」，很容易查错方向（iOS 上踩过同一个坑）。
- **悬浮球默认走应用内浮层**（挂到当前 Activity 的 window decor），**不默认申请
  `SYSTEM_ALERT_WINDOW`**：那是要用户去设置页手动开的敏感权限，还会影响宿主上架。
  跨应用悬浮窗作为可选能力，由宿主自己申请后开启。
- **厂商 ROM 会自己加码**：后台限制、自启动管理、省电策略。**"我这台过了"不等于"Android 过了"**——
  每次交付写清楚在哪个版本、哪台机器上验的。
- **ABI 与体积**：libwebrtc aar 带 `arm64-v8a` / `armeabi-v7a` / `x86_64`，会显著撑大包体。
  宿主要能按需裁剪（`abiFilters`），README 里写明各 ABI 的体积。
- **给消费者提供 `consumer-rules.pro`**：宿主开 R8 时 `org.webrtc` 的 native 回调类不能被混淆掉。

## 9. UI（仅 call-uikit）

- **用原生 View + ViewBinding，不用 Compose**（2026-09-05 定）：`SurfaceViewRenderer` 本就是 View；
  UIKit 是要塞进别人 App 的库，不该把 Compose 运行时与 Material 依赖强加给宿主；
  且 iOS 的 Kit 用的是 UIKit 而非 SwiftUI，同形态照着移植最省事。
  **宿主自己是 Compose 应用没关系**——View 能用 `AndroidView` 嵌进 Compose，反过来才麻烦。

- **通话页固定深色、不随宿主主题**（对齐草图 §01；FaceTime / Telegram 同做法）。
  颜色集中在 `KitTheme`，禁止在组件里硬编码色值。
- 控制按钮统一 56dp 圆形；**开启态白底黑字**；挂断恒红、接听恒绿。
- **图标一律用矢量（VectorDrawable）**，不用 emoji 当图标——iOS 上踩过：emoji 靠字体回退，
  设备上会变成方框问号。
- UIKit 的页面不入宿主导航栈：来电用独立 Activity（`showWhenLocked` / `turnScreenOn` 由宿主决定），
  横幅与悬浮球用应用内浮层——**任何界面都能被来电覆盖**。
- 文案集中在 `strings.xml`，**默认文案以草图 §09 文案表为准**；宿主可覆盖（同名资源即可）。
- 无障碍：所有按钮有 `contentDescription`；不用颜色作为唯一信息载体
  （静音态除了变色还要换图标）。

## 10. 测试与「完成的定义」

- **每加一个功能就配单测**。状态机与信令编解码是**必须**有测试的部分。
- 状态机跑 `im-rtc-server/docs/conformance/*.json` 的**一致性向量**，与另外四端同一份。
- **能力状态写进 `../im-rtc-server/docs/CLIENT_PARITY.md`**，那是逐端逐特性的单一真相源；
  本仓 `current_task.md` 只写「当前在做什么、坑在哪」，不重复 ✅。
  **只读引用，不许在本仓复制一份**。
- 纯逻辑（状态机、帧编解码、格子布局计算）用 JUnit 直接跑在 JVM 上，
  **不需要模拟器、不需要 Robolectric**。做不到就说明 §1 那条分层被破坏了。
- **视图层的行为也走真机，不引 Robolectric**（2026-09-07 拍板）。代价是这类 bug
  单测拦不住，所以**规则写在这里，靠评审与真机兜**：

  > **任何要挂共享渲染器的容器，`addView` 之前必须先 `(view.parent as? ViewGroup)?.removeView(view)`。**

  渲染器是**一个 uid 一份、整通复用**的（`IMCallKit.videoViewFor`），换容器时它多半
  还挂在上一个容器上，不摘就是 `IllegalStateException: The specified child already has
  a parent`，当场崩在主线程。同一条规则目前有三个实现点——`IMVideoTile` /
  `IMCallGridView` / `IMFloatingBubble`，**小窗那个漏过一次**（1v1 视频里点小窗必崩）。
  再加第四个容器时照抄前三个。

  同一处还要**判重**（`videoHost.getChildAt(0) === view` 就直接返回）：挂载点大多
  挂在每秒都会跑的 render 上，不判重就是每秒把渲染器摘一次挂一次，`SurfaceView`
  的 surface 跟着销毁重建，症状是画面闪而不是报错。

- **音视频链路一律真机验收**，且要写清楚测了什么：接通 / 静音互见 / 翻转摄像头 /
  切后台 / 息屏 / 蓝牙耳机切换 / 弱网。
- **与另外三端互打**才算通：Android ↔ Web、Android ↔ iOS 各一次。
- `./scripts/test.sh` 是唯一测试入口。**别手拼 gradle 命令行**（参数漂移会导致「本地过了 CI 挂」）。

## 11. 提交与协作

- 提交信息格式：`类型(模块): 描述`，例如 `feat(engine): 通话状态机跑通一致性向量`。
  类型取 `feat / fix / perf / refactor / docs / test / chore`。
- **一律先在 worktree 上改，改完再合回 main。不许直接在 main 的工作区改代码。**
  （2026-09-10 起。此前的约定是「直接在 main 提交、不先开分支」，那是单会话时代的规矩。）

  **为什么**：现在同时有好几个会话在并行开发同一个仓。两个会话同时往 main 的工作区
  写文件会互相覆盖，而且**谁也看不见对方改了什么**——`git status` 里混着两个人的改动，
  提交时只能靠猜哪些是自己的。worktree 各有各的工作区，这类事从根上不会发生。

  ```bash
  git worktree add .claude/worktrees/<名字> -b <分支名>   # 开
  # ……在那个目录里改、跑 ./scripts/test.sh、提交……
  git merge --no-ff <分支名>                              # 回到 main 合
  git worktree remove .claude/worktrees/<名字>            # 收
  ```

  **合之前先 `git fetch` 并看一眼 main 动没动过**：并行开发里 main 随时可能已经前进。
  **合完要在 main 上再跑一次 `./scripts/test.sh`**——两个各自都绿的分支合到一起可以是红的，
  git 只保证文本不冲突，不保证语义。（真踩过：同一个文件被两边各加了几十行，
  各自都在 600 行体量红线内，合完就超了。）

  worktree 里跑 `./scripts/test.sh` **不需要再设 `RTC_CONFORMANCE_DIR`**
  （2026-09-10 修好了）。以前要设，是因为找一致性向量用的是 `../im-rtc-server`，
  而 worktree 的根在 `.claude/worktrees/<分支>/`，`..` 指向的是 worktrees 目录。

  > **顺带记一条教训**：「兄弟仓在哪」这种事，一个仓里往往有**两个地方**各自算了一遍——
  > `scripts/test.sh` 一处，测试代码里再一处（web 的 `test/vectors.ts`、
  > iOS 的 `Vectors.swift`）。**只修脚本那处更糟**：原先脚本先失败、报错还算清楚；
  > 修好之后测试才跑到，报出来的信息反而更难懂。
  > 现在统一成 Android 一直用的形状——**脚本算一次，`export` 给测试运行器**，
  > 测试侧那份按「主检出 / `.claude/worktrees/<分支>`」两种布局算出兄弟仓**唯一**该在的位置
  > （本仓算在 `call-engine/build.gradle.kts` 的 `conformanceDir`，经系统属性交给测试），
  > 于是不走脚本直接 `./gradlew` / `npx vitest` / `swift test` 也能工作。
  > **别写成「往上逐级找到根」**：同级缺失时它会爬出本仓、捡到上层某份旧的 im-rtc-server，
  > 拿旧向量跑绿（web 2026-09-10 修过，本仓 2026-09-17 跟上）。

  **例外**：纯文档的小改（typo、补一句说明）可以直接在 main 上做，
  但只要动到代码或跨仓契约，就走 worktree。
- 提交前 pre-commit 跑体量门禁；被拦了就拆分，别 `--no-verify`。

## 12. 不做什么（刻意的边界）

- **不做宿主业务界面**：消息气泡、会话列表、群横幅。Demo 的通话记录页是示范不是要求。
- **不内置好友/联系人系统**：Demo 的联系人来自本地文件，宿主用自己的。
- **不在 Engine 里塞业务概念**：Engine 只认 `userId` / `roomId` / `callId`，
  不认「群」「会话」「好友」。群名之类的展示信息由宿主经 `userData` 透传。
- **MVP 不做锁屏来电**（需 FCM 高优先级推送 + Telecom/`ConnectionService`），
  只覆盖 App 前台且信令在线时的来电。这条要写进每次交付说明，不许含糊。
- **不共享桌面端的 C++ 核心**（2026-09-05 拍板）：Android 走 Kotlin 独立实现，
  理由见 [README](README.md)。要翻这个案，先改设计文档 §8。
- **不引重型第三方框架**：DI 框架、RxJava、第三方 UI 库一律不引；
  网络只用 OkHttp（媒体已经带了它的传递依赖）。
