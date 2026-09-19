package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.protocol.IMJsonWriter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 调试密钥本地签票（server `docs/design/DEBUG_KEY_DESIGN.md` §4）。
 *
 * **仅联调用。没有后台、想先把通话跑通时，在本机用 `dbg-` 开头的调试密钥自己签一张接入票。**
 * 上线必须换成宿主后端调 `POST /v1/tokens` 换票——调试密钥的 secret 一旦进了客户端包就等于公开。
 *
 * **只应在 debug 构建里调用。** 库模块拿不到宿主的 `BuildConfig`，所以这里无法替你判断；
 * 请宿主自己用 `if (BuildConfig.DEBUG)` 包住调用点（并让 secret 只出现在 debug 源集 / 本地配置里）。
 * 每次调用都会打一条 WARN 日志提醒。
 *
 * 不依赖 `android.util.Base64` / `java.util.Base64`（后者要 API 26，本库 minSdk 24），
 * 所以纯 JVM 单测可直接跑。
 */
object IMDebugTokenGenerator {

    private const val TAG = "debug-token"
    private const val ISSUER = "im-rtc-server"
    private const val DEFAULT_TTL_SEC = 12 * 3600L
    private const val MIN_TTL_SEC = 60L
    private const val MAX_TTL_SEC = 30 * 24 * 3600L
    private const val MAX_UID_BYTES = 64
    private const val KEY_PREFIX = "dbg-"
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * 生成 HS256 接入票。
     *
     * @param ttlSec 有效期秒数；缺省或 0 = 12 小时，钳到 [60, 2592000]（30 天）。
     * @param nowUnixSec 当前 Unix 秒，测试时注入。
     * @throws IllegalArgumentException uid 空 / 含空白 / 超 64 字节，appId 或 secret 为空，keyId 不以 `dbg-` 开头。
     */
    @JvmStatic
    @JvmOverloads
    fun generateDebugToken(
        appId: String,
        keyId: String,
        secret: String,
        uid: String,
        deviceId: String? = null,
        ttlSec: Long = 0,
        nowUnixSec: Long = System.currentTimeMillis() / 1000,
    ): String {
        validate(appId, keyId, secret, uid)
        IMRTCLog.w(TAG, "仅联调：正在用调试密钥在本地签票（kid=$keyId）。上线请换成后端 POST /v1/tokens，且只在 debug 构建里调用")

        val ttl = when {
            ttlSec <= 0L -> DEFAULT_TTL_SEC
            else -> ttlSec.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC)
        }
        val header = IMJson.Obj(linkedMapOf("alg" to IMJson.Str("HS256"), "typ" to IMJson.Str("JWT"), "kid" to IMJson.Str(keyId)))
        val claims = linkedMapOf<String, IMJson>(
            "iss" to IMJson.Str(ISSUER),
            "sub" to IMJson.Str(uid),
            "aud" to IMJson.Str(appId),
            "exp" to IMJson.Num(nowUnixSec + ttl),
            "iat" to IMJson.Num(nowUnixSec),
            "scope" to IMJson.Str("access"),
        )
        if (!deviceId.isNullOrEmpty()) claims["did"] = IMJson.Str(deviceId)

        val signingInput = base64Url(IMJsonWriter.write(header).toByteArray(Charsets.UTF_8)) + "." +
            base64Url(IMJsonWriter.write(IMJson.Obj(claims)).toByteArray(Charsets.UTF_8))
        return signingInput + "." + hmacSha256Base64Url(secret, signingInput)
    }

    private fun validate(appId: String, keyId: String, secret: String, uid: String) {
        require(appId.isNotEmpty()) { "appId 不能为空" }
        require(secret.isNotEmpty()) { "secret 不能为空" }
        require(keyId.startsWith(KEY_PREFIX)) { "keyId 必须以 $KEY_PREFIX 开头（只接受调试密钥，生产密钥不许进客户端）" }
        require(uid.isNotEmpty()) { "uid 不能为空" }
        require(uid.none { it.isWhitespace() }) { "uid 不能含空白" }
        require(uid.toByteArray(Charsets.UTF_8).size <= MAX_UID_BYTES) { "uid 不能超过 $MAX_UID_BYTES 字节" }
    }

    /** HMAC-SHA256 后 base64url 无填充；向量 `hmac_cases` 钉的就是这一步。 */
    internal fun hmacSha256Base64Url(secret: String, signingInput: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
    }

    /** base64url，无填充。手写是因为 java.util.Base64 要 API 26、android.util.Base64 在 JVM 单测里是桩。 */
    internal fun base64Url(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            sb.append(URL_ALPHABET[b0 shr 2])
            sb.append(URL_ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) sb.append(URL_ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) sb.append(URL_ALPHABET[b2 and 63])
            i += 3
        }
        return sb.toString()
    }
}
