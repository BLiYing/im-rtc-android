package com.imrtc.engine.webrtc

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver

/**
 * **上行怎么编**这一摊策略：三层的 encodings、码率预算播种、降级偏好、codec 偏好。
 *
 * 从 [IMWebRTCAdapter] 里抽出来的协作对象（CONVENTIONS §2）：那边已经顶着 600 行红线，
 * 而这几件事是一簇自洽的关注点——**它们全都只在「刚挂上 transceiver」那一刻做一次**，
 * 而且全都只需要「档位 + 那条 PeerConnection / transceiver」。
 *
 * # 四件事的依赖关系，别拆开看
 *
 * · [encodings] 造三层；
 * · [seedUplinkBudget] 告诉 BWE 这三层一共要多少（否则顶层被分到 0 bps）；
 * · [preferH264Codec] 让硬编真的被协商上（否则 simulcast adapter 成了负优化）；
 * · [preferResolutionOverFramerate] CPU 吃紧时保分辨率。
 *
 * 前两件是一对（层数 ↔ 总预算），后两件是一对（硬编 ↔ 保分辨率都为了「看清字」）。
 */
internal class IMUplinkPolicy(
    private val videoProfile: IMVideoProfile,
    /** 见 [IMWebRTCAdapter] 的同名参数。**与编码器工厂那一位必须同值**。 */
    private val preferHardwareH264: Boolean,
    /** 取 `PeerConnectionFactory`。注入成函数而不是直接持有：工厂归 [IMPeerConnections] 管。 */
    private val factory: () -> PeerConnectionFactory,
) {

    /**
     * 三层 simulcast 的 encodings，或者单层那一条。
     *
     * rid 必须是 `l`/`m`/`h`——与服务端的层选择、`max_layer` 枚举同名。
     */
    fun encodings(simulcast: Boolean): List<RtpParameters.Encoding> = if (simulcast) {
        videoProfile.simulcastLayers.map { encoding(it.rid, it.scaleDownBy, it.bitrateBps) }
    } else {
        listOf(encoding("h", scale = 1.0, maxBitrateBps = videoProfile.maxBitrateBps))
    }

    /**
     * 挂好视频 transceiver 之后一次性把四件事都办了。**顺序有讲究**：
     * codec 偏好要在生成 offer 之前设上，否则 SDP 已经定稿了。
     */
    fun applyToVideo(connection: PeerConnection, transceiver: RtpTransceiver?, cid: String, simulcast: Boolean) {
        preferH264Codec(transceiver)
        if (simulcast) seedUplinkBudget(connection)
        preferResolutionOverFramerate(connection, cid)
    }

    /**
     * 把**上行真实预算**告诉 BWE，别让它从 300 kbps 起爬。
     *
     * # 不做这一步会怎样（真机日志复现过）
     *
     * libwebrtc 发送侧 BWE 的起始估计默认是 300 kbps。我们推三层，总共要
     * [IMVideoProfile.simulcastUplinkBudgetBps]（720p 是 2.15 Mbps），
     * 而 `SimulcastRateAllocator` 在总码率不够时**自底向上**分配、给顶层分 0 bps。
     * 于是开局只有 `l` 层出包，`m`/`h` 要等 BWE 一路探测上来才活：
     * 服务端日志里是 `上行层存活性变化 layer=h live=False`，订阅端看到的是糊成
     * 320×180 的画面。实测一通 1v1 里 h 层死了 37 秒；另一通群通 27 秒全程只有 `l`。
     *
     * **这不是调参，是把一个本来就知道的数字填进去。** 服务端对下行做的是同一件事
     * （`internal/sfu/bwe.go` 的 `bweInitialBitrate = 2_000_000`，注释写着
     * 「种子给低了会在开局把所有人砸到 l」）——上行同理，只是一直没人填。
     *
     * 三个参数的取法：
     *  - min = 最低那层的码率。再低就不是「糊」而是「没画面」，那不该由 BWE 替用户决定
     *    （与 `bwe.go` 的 `capForEstimate` 「撑不住全 l 时仍然返回 l」同一条取舍）。
     *  - start = 总预算。**只影响开局**，之后仍由 TWCC 反馈接管；
     *    网络真的窄，BWE 第一批反馈就会把它压下去，不会一直硬灌。
     *  - max = 总预算。再高也没用——我们一共就只要这么多，别让它白探测。
     *
     * 单层发布（`simulcast=false`）不需要：那时总量就等于 `maxBitrateBps`，
     * 300 kbps 起爬也只是第一秒略糊，不会有整层拿不到码率这种事。
     */
    private fun seedUplinkBudget(connection: PeerConnection) {
        val budget = videoProfile.simulcastUplinkBudgetBps
        val floor = videoProfile.simulcastLayers.first().bitrateBps
        // 失败不该让通话挂掉：拿不到就是退回 libwebrtc 的默认起点，画面糊一会儿而已。
        if (connection.setBitrate(floor, budget, budget)) {
            IMRTCLog.i("media", "上行预算已播种 min=$floor start=$budget max=$budget（档位 ${videoProfile.name}）")
        } else {
            IMRTCLog.w("media", "上行预算播种失败，退回 libwebrtc 默认起点（开局可能只有 l 层）")
        }
    }

    /**
     * 把 **H.264 排到 offer 的 codec 列表最前面**，让硬编真的被协商上。
     *
     * # 为什么必须显式排
     *
     * `DefaultVideoEncoderFactory` 把软件编解码器排在前面（VP8/VP9 来自
     * `SoftwareVideoEncoderFactory`），于是 offer 里 VP8 就在第一位、自动胜出。
     * **没人选过 VP8，它只是默认值**——而 iOS 那边 `RTCDefaultVideoEncoderFactory`
     * 恰好把 H.264 排前面，所以 iOS 一直在用硬编。两端的「默认」正好相反。
     *
     * # 为什么不能只换工厂
     *
     * `SimulcastVideoEncoderFactory` 对 **VP8 是负优化**：libvpx 原生支持 simulcast
     * （一个实例内编三路、共享分析），套上 adapter 就变成三个独立实例，CPU 反而更高。
     * 所以「套了 adapter 却仍然协商到 VP8」比什么都不改还差。
     * 这个方法与 `IMPeerConnections.encoderFactory` 由同一个 [preferHardwareH264]
     * 控制，**同进同退**。
     *
     * # 不剔除 VP8，只是把 H.264 提前
     *
     * 列表里仍然留着 VP8，所以对端不支持这一档 H.264 时**还能静默回落**，
     * 不会变成「协商不出视频」。代价是回落之后 adapter 那个负优化会生效——
     * 所以日志里要能看出到底用的是哪个（见末尾那行 INFO，配合服务端的
     * `上行 Track 已接入 … codec=` 一起看）。
     *
     * # 实际会落在 Constrained Baseline 上
     *
     * Android 硬编提供 `640c1f`（Constrained High）与 `42e01f`（Constrained Baseline）；
     * 服务端 Pion 注册的是 `64001f`（普通 High）与 `42e01f`。**High 那一对差一个
     * 约束位、profile 枚举不同，匹配不上**，所以实际落在 Constrained Baseline。
     * 要用上 High 得先往服务端加 `640c1f`，那是另一刀
     * （还要走 `CLIENT_PARITY.md` 的 H.264 跨端三条实测）。
     *
     * 失败不影响通话：排序没设上就是退回默认顺序，记一条日志。
     */
    private fun preferH264Codec(transceiver: RtpTransceiver?) {
        if (!preferHardwareH264) return
        val sender = transceiver ?: run {
            IMRTCLog.w("media", "没拿到 transceiver，H.264 偏好没设上")
            return
        }
        val capabilities = factory()
            .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            ?.codecs ?: return
        val ordered = h264First(capabilities)
        if (ordered == null) {
            IMRTCLog.w("media", "这台机器没有 H.264 编码能力，保持默认顺序（会走 VP8）")
            return
        }
        val h264 = ordered.filter { it.name.equals(H264, ignoreCase = true) }
        // 成功时返回的也是一个非 null 的 RtcError（isSuccess=true），只判 null 会把成功记成失败——
        // 2026-09-10 真机就是这样：日志报「没设上」，服务端却明明协商到了 H264。
        val result = sender.setCodecPreferences(ordered)
        if (result == null || result.isSuccess) {
            IMRTCLog.i(
                "media",
                "codec 偏好已设：H.264 优先（${h264.size} 档）" +
                    h264.joinToString(prefix = " ", separator = " ") {
                        it.parameters["profile-level-id"] ?: "?"
                    },
            )
        } else {
            // 编码器工厂的默认顺序本来就是硬件 H.264 在前，所以没设上不等于退回 VP8；
            // 实际用了哪个 codec 以服务端「上行 Track 已接入 … codec=」为准。
            IMRTCLog.w(
                "media",
                "codec 偏好没设上（${result.error()?.message ?: "未知原因"}），保持工厂默认顺序——实际 codec 看服务端「上行 Track 已接入」",
            )
        }
    }

    /**
     * CPU / 码率吃紧时**掉帧率，不掉分辨率**。
     *
     * # 为什么
     *
     * libwebrtc 不设这一项时默认 `BALANCED`——分辨率与帧率一起降。真机实测
     * （2026-09-10，OPPO 推三层 VP8）h 层从 720×1280 掉到 **540×960** 并标着
     * `受限=cpu`（见 [IMUplinkStats] 打的「上行层实况」）。
     *
     * 而本产品最吃分辨率的场景是**看清画面里的字**（共享屏幕、对着文档拍）——
     * 那种场景下 15fps 完全够用，**分辨率掉一档就直接看不清了**。
     * 所以这里显式反转默认取舍。
     *
     * # 代价，说清楚
     *
     * CPU 真顶不住时帧率会掉得很低（实测见过 fps=1~4），画面变成近乎静止的幻灯片。
     * 那仍然比「糊成一团但很流畅」好——**至少信息还在**。
     * 如果以后有「流畅优先」的场景（比如纯聊天），该由宿主选，不该在这里写死。
     *
     * 只对视频生效；音频没有分辨率这回事。失败不影响通话，记一条日志就够。
     */
    private fun preferResolutionOverFramerate(connection: PeerConnection, cid: String) {
        val sender = connection.senders.firstOrNull { it.track()?.id() == cid } ?: run {
            IMRTCLog.w("media", "找不到 cid=$cid 的 sender，降级偏好没设上")
            return
        }
        val params = sender.parameters ?: return
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        if (sender.setParameters(params)) {
            IMRTCLog.i("media", "编码降级偏好=MAINTAIN_RESOLUTION（宁可掉帧率也保分辨率）")
        } else {
            IMRTCLog.w("media", "降级偏好没设上，退回 libwebrtc 默认的 BALANCED（分辨率会跟着降）")
        }
    }

    private fun encoding(rid: String, scale: Double, maxBitrateBps: Int) =
        RtpParameters.Encoding(rid, true, scale).apply { this.maxBitrateBps = maxBitrateBps }

    /** `internal` 而不是 `private`：[h264First] 要被同模块的单测调到。类本身是 internal，不扩公开面。 */
    internal companion object {
        const val H264 = "H264"

        /**
         * 把 H.264 排到最前、其余**保持原有相对顺序**。没有 H.264 时返回 `null`。
         *
         * 摘成纯函数是为了能单测：这段最容易写错的地方有两处——
         * ① 把非 H.264 的也过滤掉了（回落路径就没了，对端不支持这一档就直接没视频）；
         * ② 排序不稳定（同一台机器两次协商结果不同，之后查起来毫无头绪）。
         * 两条都没有任何报错，只会表现成「画质莫名其妙」。
         */
        fun h264First(
            capabilities: List<RtpCapabilities.CodecCapability>,
        ): List<RtpCapabilities.CodecCapability>? {
            val (h264, rest) = capabilities.partition { it.name.equals(H264, ignoreCase = true) }
            if (h264.isEmpty()) return null
            return h264 + rest
        }
    }
}
