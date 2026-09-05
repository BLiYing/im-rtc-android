package com.imrtc.demo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Demo 用的两个 REST 接口。**这一层属于宿主，不属于 SDK。**
 *
 * - `POST /v1/demo/login`：**免密登录**，填用户名就换一枚 token。
 *   它只在服务端带 `-demo-login` 时存在，生产构建根本没有这条路由。
 *   **换成你自己的后台**：把这里换成你们签发 token 的接口即可，Engine 只认那枚 token。
 * - `POST /v1/rooms`：建会议房，回 `room_id` + `room_token`。
 *   通话房不走这里——那种房由 `call.invite` 隐式创建，票随 `call.connected` 下发。
 *
 * 这里用 `org.json` 是**故意的**：它是宿主代码，不是 SDK 代码，跑在真设备上没有空壳桩问题。
 * SDK 里禁用 `org.json` 的理由是单测会假绿（CONVENTIONS 技术栈那节），与这里无关。
 */
internal class DemoApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class LoginResult(val token: String, val uid: String)

    data class RoomResult(val roomId: String, val roomToken: String)

    @Throws(IOException::class)
    fun demoLogin(username: String): LoginResult {
        val json = post("/v1/demo/login", null, JSONObject().put("username", username))
        return LoginResult(json.optString("token"), json.optString("uid", username))
    }

    /**
     * 建会议房，**再单独取一枚进房票**。
     *
     * 这是两步，不是一步：`POST /v1/rooms` 只回 `room_id`，票要走
     * `POST /v1/rooms/{id}/tokens` 再要一次。第一版把它们当成一步（以为建房就带票），
     * 结果 `room.join` 被服务端以 `1101 token_invalid` 拒掉——**真机上一跑就露馅**。
     */
    @Throws(IOException::class)
    fun createMeetingRoom(token: String, deviceId: String): RoomResult {
        val created = post("/v1/rooms", token, JSONObject().put("kind", "meeting"))
        return joinTicket(token, created.optString("room_id"), deviceId)
    }

    /**
     * 取一枚进房票。
     *
     * **`device_id` 必须与 Engine 握手时用的那个一模一样**——房票绑定
     * (room_id, uid, device_id)，对不上就是 `token_invalid`。
     */
    @Throws(IOException::class)
    fun joinTicket(token: String, roomId: String, deviceId: String): RoomResult {
        val json = post("/v1/rooms/$roomId/tokens", token, JSONObject().put("device_id", deviceId))
        return RoomResult(json.optString("room_id", roomId), json.optString("room_token"))
    }

    private fun post(path: String, token: String?, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JSON))
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 把服务端的 error 文案带出来——只说「失败了」等于让人去猜。
                val hint = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IOException("HTTP ${response.code}${if (hint.isNullOrEmpty()) "" else "：$hint"}")
            }
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
