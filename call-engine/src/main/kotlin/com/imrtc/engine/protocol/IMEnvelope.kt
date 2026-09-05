package com.imrtc.engine.protocol

/**
 * 信封 `{type, req_id, ts, data}` —— 协议 §2。
 *
 * 四个字段**都是必填**，一个都不能省：
 * - `type`   非空字符串；
 * - `req_id` 请求/应答成对，事件恒为空串（**空串不等于缺失**，缺了就是非法信封）；
 * - `ts`     整数毫秒；
 * - `data`   对象，**不能是 null**，没有内容就写 `{}`。
 *
 * 「req_id 缺失非法但可以是空串」这条看着别扭，但它换来一件事：接收方不需要区分
 * 「没写」和「写了空」——**这两种情况在五种语言里的表现从来就不一致**。
 */
internal data class IMEnvelope(
    val type: String,
    val reqId: String,
    val timestampMs: Long,
    val data: Map<String, IMJson>,
) {

    /** 服务端主动推的事件，req_id 恒为空串。 */
    val isEvent: Boolean get() = reqId.isEmpty()

    /** 序列化成一帧文本。 */
    @Throws(IMRtcException::class)
    fun encode(): String {
        val text = IMJsonWriter.write(
            IMJson.Obj(
                linkedMapOf(
                    "type" to IMJson.Str(type),
                    "req_id" to IMJson.Str(reqId),
                    "ts" to IMJson.Num(timestampMs),
                    "data" to IMJson.Obj(data),
                ),
            ),
        )
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FRAME_BYTES) {
            throw IMRtcException(IMErrorCode.FRAME_TOO_LARGE, "帧 $bytes 字节 > 上限 $MAX_FRAME_BYTES")
        }
        return text
    }

    /**
     * 按帧声明把 `data` 补齐默认值、校验类型。
     *
     * 未知帧类型抛 [IMErrorCode.UNKNOWN_TYPE]——**不静默放行**：放行等于让上层拿到一个
     * 没人校验过的字典，那种数据迟早以奇怪的方式崩在别处。
     * 上层拿到这个错误后**客户端要静默忽略这一帧**（§2.3 的前向兼容），
     * 但那是上层的处置，不是解码器的事。
     */
    @Throws(IMRtcException::class)
    fun decodedData(): Map<String, IMJson> {
        val fields = IMFrameRegistry.fields(type)
            ?: if (IMFrameRegistry.isReserved(type)) {
                throw IMRtcException(IMErrorCode.NOT_IMPLEMENTED, "$type 是会议层留位帧，v1 不实现")
            } else {
                throw IMRtcException(IMErrorCode.UNKNOWN_TYPE, "未知帧类型 $type")
            }
        return FieldCodec.decode(fields, data)
    }

    companion object {
        /** 单帧上限（协议 §1.3），超了服务端直接以 4400 关连接。 */
        const val MAX_FRAME_BYTES = 65536

        /** 应答帧的后缀：`room.join` 的应答是 `room.join.ok`。 */
        const val OK_SUFFIX = ".ok"

        fun okType(type: String): String = type + OK_SUFFIX

        /**
         * 解一帧。
         *
         * 顺序是**先信封、再编码硬规则**：信封坏了就没必要再往里看，
         * 而且两类错误的码不同（`bad_envelope` vs `bad_params`），先后颠倒会报错码。
         */
        @Throws(IMRtcException::class)
        fun decode(raw: String): IMEnvelope {
            val bytes = raw.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_FRAME_BYTES) {
                throw IMRtcException(IMErrorCode.FRAME_TOO_LARGE, "帧 $bytes 字节 > 上限 $MAX_FRAME_BYTES")
            }

            val parsed = try {
                IMJsonParser.parse(raw)
            } catch (e: IMJsonError) {
                throw IMRtcException.from(e)
            }
            val top = (parsed as? IMJson.Obj)?.fields
                ?: throw IMRtcException(IMErrorCode.BAD_ENVELOPE, "信封必须是 JSON 对象")

            val type = (top["type"] as? IMJson.Str)?.value
            if (type.isNullOrEmpty()) {
                throw IMRtcException(IMErrorCode.BAD_ENVELOPE, "type 缺失或不是非空字符串")
            }
            // **不是「可以省略」**：事件用空串表达，省略就是非法信封。
            val reqId = (top["req_id"] as? IMJson.Str)?.value
                ?: throw IMRtcException(IMErrorCode.BAD_ENVELOPE, "req_id 缺失；事件请显式写空串")
            val ts = (top["ts"] as? IMJson.Num)?.value
                ?: throw IMRtcException(IMErrorCode.BAD_ENVELOPE, "ts 缺失或不是整数毫秒")
            val data = (top["data"] as? IMJson.Obj)?.fields
                ?: throw IMRtcException(IMErrorCode.BAD_ENVELOPE, "data 缺失或不是对象；没有内容请写 {}")

            Discipline.check(data)
            return IMEnvelope(type = type, reqId = reqId, timestampMs = ts, data = data)
        }

        /**
         * 造一帧待发送的请求：**从「已填好默认值的 data」起手**，再改字段。
         *
         * 这个入口存在的唯一理由就是挡住「发送侧默认值陷阱」——直接写
         * `mapOf("room_id" to ...)` 会漏掉 `auto_subscribe`，而显式写 `false`
         * 又会把默认的 `true` 覆盖掉，**两种写法都会让人进了房收不到任何流**。
         */
        fun request(type: String, reqId: String, timestampMs: Long, mutate: (MutableMap<String, IMJson>) -> Unit = {}): IMEnvelope {
            val fields = IMFrameRegistry.fields(type)
                ?: throw IMRtcException(IMErrorCode.UNKNOWN_TYPE, "未知帧类型 $type")
            val data = LinkedHashMap(FieldCodec.defaults(fields))
            mutate(data)
            return IMEnvelope(type, reqId, timestampMs, data)
        }
    }
}
