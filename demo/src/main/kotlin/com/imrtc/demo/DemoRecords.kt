package com.imrtc.demo

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条通话记录。存成 JSON 进 SharedPreferences——Demo 不引数据库。 */
data class DemoRecord(
    val callId: String,
    val peer: String,
    val mediaType: String,
    val isGroup: Boolean,
    /** "caller" 或 "callee"。未接来电＝被叫且时长为 0。 */
    val role: String,
    val reason: String,
    val durationSec: Long,
    val endedAtMs: Long,
)

/**
 * 通话记录的存取。
 *
 * 从 `DemoSession` 里拿出来是因为它**与会话生命周期无关**：那边管的是登录、换票、
 * 引擎回调，这边只是一张表的序列化。两件事挨在一起时，`DemoSession` 长到了 598 行、
 * 逼近 600 的红线，而真正想读「登录到底怎么走」的人要先翻过一整段 JSON 拼装。
 *
 * **读坏了就当空表**：Demo 的记录不值得为它崩一次 app，也不值得弹个框问用户。
 */
object DemoRecordStore {
    /** 只留最近 100 条，Demo 不做翻页。 */
    private const val MAX_RECORDS = 100

    fun load(prefs: SharedPreferences): List<DemoRecord> {
        val text = prefs.getString(KEY_RECORDS, "").orEmpty()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index -> parse(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    fun save(prefs: SharedPreferences, records: List<DemoRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun parse(json: JSONObject) = DemoRecord(
        callId = json.optString("call_id"),
        peer = json.optString("peer"),
        mediaType = json.optString("media_type", "audio"),
        isGroup = json.optBoolean("is_group"),
        role = json.optString("role", "caller"),
        reason = json.optString("reason"),
        durationSec = json.optLong("duration_sec"),
        endedAtMs = json.optLong("ended_at_ms"),
    )

    private fun encode(record: DemoRecord): JSONObject = JSONObject()
        .put("call_id", record.callId)
        .put("peer", record.peer)
        .put("media_type", record.mediaType)
        .put("is_group", record.isGroup)
        .put("role", record.role)
        .put("reason", record.reason)
        .put("duration_sec", record.durationSec)
        .put("ended_at_ms", record.endedAtMs)
}
