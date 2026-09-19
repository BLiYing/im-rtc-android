package com.imrtc.engine

import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonError
import com.imrtc.engine.protocol.IMJsonParser
import com.imrtc.engine.statemachine.Wire
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * 通话记录查询：`GET /v1/calls`（server 设计文档 §4.5）。
 *
 * **宿主不一定要用它**：很多宿主拿 `onCallEnd` 自己存、或拿 webhook 落自己的库就够了。
 * 想让「换设备、重装之后记录还在」，或者不想自己存，就调 `IMCallEngine.fetchCallHistory`。
 *
 * 走的是当前登录用的那枚接入票（含 `updateToken` 换过的），服务端据此**只返回本人参与过的通话**——
 * 所以没有 `uid` 参数，也不能查别人。
 */

/** 通话记录里的一位成员。[state] 是这位成员在这通电话里的结局，原样透传。 */
data class IMCallHistoryMember(val uid: String, val state: String)

/**
 * 一条通话记录，字段与服务端 `GET /v1/calls` 一一对应。
 *
 * @property reason 通话的最终结局（`hangup`、`cancel`、`reject`、`no_answer`…），**不分角色**：
 *   要显示「已取消」还是「对方已取消」，用 [caller] 与自己的 `uid` 比出角色再定文案。
 * @property mediaType `audio` 或 `video`。
 */
data class IMCallHistoryRecord(
    val callId: String,
    val roomId: String,
    val caller: String,
    val mediaType: String,
    val isGroup: Boolean,
    val reason: String,
    val endedBy: String,
    val durationSec: Int,
    val startedAtMs: Long,
    val connectedAtMs: Long,
    val endedAtMs: Long,
    val userData: String,
    val chatGroupId: String,
    val members: List<IMCallHistoryMember>,
)

/**
 * 一页通话记录（按发起时间倒序）。
 *
 * @property nextCursor 下一页的游标，传给下一次 `fetchCallHistory(cursor = …)`。**null 表示已经到底**。
 */
data class IMCallHistoryPage(val records: List<IMCallHistoryRecord>, val nextCursor: Long?)

/** 请求拼装与应答解析，纯函数、不碰网络，单测直接验。 */
internal object IMCallHistory {

    /** 服务端每页上限（`maxCallLimit`）。超过它服务端也只回这么多，「到底」的判据就不成立了，所以本地先夹住。 */
    const val MAX_LIMIT = 200
    const val DEFAULT_LIMIT = 20

    /** 信令地址推出 REST 根：`ws→http`、`wss→https`，去掉末尾的 `/v1/ws`。推不出返回 null。 */
    fun restBase(signalingUrl: String): HttpUrl? {
        val scheme = signalingUrl.substringBefore("://", "").lowercase()
        val rest = signalingUrl.substringAfter("://", "")
        val mapped = when (scheme) {
            "ws" -> "http"
            "wss" -> "https"
            "http", "https" -> scheme
            else -> return null
        }
        val url = "$mapped://$rest".toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.removeSuffix("/v1/ws")
        return url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).query(null).fragment(null).build()
    }

    fun buildUrl(signalingUrl: String, limit: Int, cursor: Long?): HttpUrl? {
        val base = restBase(signalingUrl) ?: return null
        val builder = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/v1/calls")
            .addQueryParameter("limit", limit.toString())
        if (cursor != null && cursor > 0) builder.addQueryParameter("cursor", cursor.toString())
        return builder.build()
    }

    /** 状态码与应答体 → 一页记录，或一个错误。 */
    fun parse(status: Int, body: String, limit: Int): Result<IMCallHistoryPage> {
        when (status) {
            200 -> Unit
            401 -> return failure(IMErrorCode.TOKEN_INVALID, "查通话记录被拒（401）：票无效或已过期")
            else -> return failure(IMErrorCode.INTERNAL, "查通话记录失败：HTTP $status")
        }
        val root = try {
            IMJsonParser.parseObject(body)
        } catch (e: IMJsonError) {
            return failure(IMErrorCode.INTERNAL, "通话记录应答解析失败：${e.message}")
        }
        val records = Wire.objects(root.fields, "calls").map(::record)
        // 服务端只要这页有数据就给 next_cursor，没有「到底」标志：
        // 不满一页就一定是最后一页，满页才交出游标（最坏多翻一页空的）。
        val next = (root.fields["next_cursor"] as? IMJson.Num)?.value
        return Result.success(IMCallHistoryPage(records, if (records.size >= limit) next else null))
    }

    private fun record(o: Map<String, IMJson>) = IMCallHistoryRecord(
        callId = Wire.str(o, "call_id"),
        roomId = Wire.str(o, "room_id"),
        caller = Wire.str(o, "caller"),
        mediaType = Wire.str(o, "media_type"),
        isGroup = Wire.flag(o, "is_group"),
        reason = Wire.str(o, "reason"),
        endedBy = Wire.str(o, "ended_by"),
        durationSec = Wire.num(o, "duration_sec").toInt(),
        startedAtMs = Wire.num(o, "started_at_ms"),
        connectedAtMs = Wire.num(o, "connected_at_ms"),
        endedAtMs = Wire.num(o, "ended_at_ms"),
        userData = Wire.str(o, "user_data"),
        chatGroupId = Wire.str(o, "chat_group_id"),
        members = Wire.objects(o, "members").map { IMCallHistoryMember(Wire.str(it, "uid"), Wire.str(it, "state")) },
    )

    private fun failure(code: IMErrorCode, message: String): Result<IMCallHistoryPage> =
        Result.failure(IMCallHistoryException(IMRTCError.of(code, message, "")))
}

/** 把 [IMRTCError] 装进 `Result.failure`，出口处再拆开。 */
internal class IMCallHistoryException(val error: IMRTCError) : Exception(error.message)

/**
 * 通话记录的 HTTP 客户端：与信令共用同一份地址与票，结果切回主线程交付。
 * 拆出来是体量红线（CONVENTIONS §2）——`IMCallEngine.kt` 已经排满了。
 */
internal class IMCallHistoryClient(
    private val signalingUrl: String,
    private val dispatcher: IMEventDispatcher,
    private val client: OkHttpClient = defaultClient,
) {
    /** 当前接入票：`login` / `updateToken` 写、`logout` 清，宿主线程读。空串 = 没登录。 */
    @Volatile
    var token: String = ""

    fun fetch(limit: Int, cursor: Long?, onResult: IMResultCallback<IMCallHistoryPage>) {
        val ticket = token
        if (ticket.isEmpty()) return deliver(onResult, null, IMRTCError.of(IMErrorCode.NOT_LOGGED_IN, "fetchCallHistory：请先 login", ""))
        val clamped = limit.coerceIn(1, IMCallHistory.MAX_LIMIT)
        val url = IMCallHistory.buildUrl(signalingUrl, clamped, cursor)
            ?: return deliver(onResult, null, IMRTCError.of(IMErrorCode.BAD_PARAMS, "信令地址无法推出 REST 地址：$signalingUrl", ""))
        val request = Request.Builder().url(url).header("Authorization", "Bearer $ticket").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                deliver(onResult, null, IMRTCError.of(IMErrorCode.NETWORK_UNREACHABLE, "查通话记录失败：${e.message}", ""))

            override fun onResponse(call: Call, response: Response) {
                val (status, body) = response.use { it.code to (it.body?.string() ?: "") }
                IMCallHistory.parse(status, body, clamped).fold(
                    { deliver(onResult, it, null) },
                    { deliver(onResult, null, (it as? IMCallHistoryException)?.error ?: IMRTCError.of(IMErrorCode.INTERNAL, it.message ?: "", "")) },
                )
            }
        })
    }

    private fun deliver(cb: IMResultCallback<IMCallHistoryPage>, page: IMCallHistoryPage?, e: IMRTCError?) =
        dispatcher.onMainThread { cb.onResult(page, e) }

    private companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        }
    }
}
