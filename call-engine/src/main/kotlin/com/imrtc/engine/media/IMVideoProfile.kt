package com.imrtc.engine.media

/**
 * 视频画质档位。
 *
 * # 为什么是「宿主给」而不是「服务端下发」
 *
 * 与换 token 是同一条边界（协议 §1.5）：**策略归宿主，Engine 不自己去要。**
 * 画质该多高取决于宿主的业务（付费档位、当前网络、机型），Engine 不认识那些东西。
 * 宿主要「后台可控」就把这个值放进自己的配置接口里下发给 App——
 * 那正是宿主后台该管的事，也不需要动 RTC 协议。
 *
 * （真要让 **RTC 服务端**统一下发，那是往 `sys.hello.ok.limits` 加字段，
 * 等于改五个仓 + 一致性向量，是单独一个任务，不该混在别的改动里。）
 *
 * # 档位怎么定
 *
 * 分辨率按主流三档；码率取的是 simulcast 最高层的目标值（协议 §3.5 的表）。
 * **`maxBitrateBps` 必须和服务端 `internal/sfu/bwe.go` 的 `bitrateHigh` 对得上**：
 * 那边拿它做带宽预算，两边不一致的话，带宽估计会按一个错的数字去决定降不降层。
 * 三端（Web 的 `media/videoProfile.ts`、iOS 的 `IMVideoProfile.swift`、这里）用同一张表。
 *
 * 是 `data class` 而不是枚举：**宿主可以自己造一档**。三个预设只是常用值，
 * 不是白名单——真实宿主从自己的配置接口拿数字，那数字未必落在这三档上。
 */
data class IMVideoProfile(
    /** 档位名，只用于日志与界面显示。 */
    val name: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    /** 最高层的目标码率。simulcast 的 m / l 层按 1/3、1/10 折算。 */
    val maxBitrateBps: Int,
) {

    /** simulcast 一层：rid + 缩小倍数 + 码率。媒体层把它翻成 `RtpParameters.Encoding`。 */
    data class Layer(
        val rid: String,
        val scaleDownBy: Double,
        val bitrateBps: Int,
    )

    /**
     * 三层的码率：h 满额、m 三分之一、l 十分之一（协议 §3.5）。
     *
     * **顺序是 l → m → h**：libwebrtc 要求 encodings 按 `scaleResolutionDownBy` 从大到小排，
     * 反了的话 native 侧会把它重排，rid 与实际分辨率就对不上号了。
     */
    val simulcastLayers: List<Layer>
        get() = listOf(
            Layer("l", 4.0, maxBitrateBps / 10),
            Layer("m", 2.0, maxBitrateBps / 3),
            Layer("h", 1.0, maxBitrateBps),
        )

    /**
     * 推 simulcast 时**上行真正要的总码率** = 三层之和（720p 是 2.15 Mbps，不是 1.5）。
     *
     * `maxBitrateBps` 是「h 一层」的数，服务端 `bwe.go` 拿它算的是**下行**预算——
     * 那边每个订阅者只收一层，所以 1.5M 是对的。但发布端要同时编出三层，
     * 账面上从来没人记过这个总和，于是踩了这个坑：libwebrtc 的发送侧 BWE
     * **默认从 300 kbps 起爬**，而 `SimulcastRateAllocator` 在总码率不够时自底向上分配、
     * 给顶层分 0 bps —— 表现就是开局只有 `l` 层出包，`h` 要等 BWE 爬上来才活。
     * 真机日志里 h 层死过 37 秒，有一通群通整 27 秒只发出了 `l`。
     *
     * 所以这个数要喂给 `PeerConnection.setBitrate` 当种子，**与服务端对下行的做法同理**
     * （`bwe.go` 的 `bweInitialBitrate = 2_000_000`，注释：「种子给低了会在开局把所有人砸到 l」）。
     *
     * **这不是新的协议数据**，是 [simulcastLayers] 的和；§3.5 那张表一个数都没动。
     */
    val simulcastUplinkBudgetBps: Int
        get() = simulcastLayers.sumOf { it.bitrateBps }

    companion object {
        @JvmField
        val P360 = IMVideoProfile("360p", 640, 360, 24, 500_000)

        @JvmField
        val P720 = IMVideoProfile("720p", 1280, 720, 30, 1_500_000)

        @JvmField
        val P1080 = IMVideoProfile("1080p", 1920, 1080, 30, 3_000_000)

        /** 缺省档位。**1080p 在九宫格里没有意义**，只是白烧上行带宽。 */
        @JvmField
        val DEFAULT = P720

        /** 三个预设，按分辨率从低到高。界面拿它列档位。 */
        @JvmField
        val PRESETS: List<IMVideoProfile> = listOf(P360, P720, P1080)
    }
}
