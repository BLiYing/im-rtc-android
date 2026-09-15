package com.imrtc.engine.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `call()` 选项的本地校验（协议 §2.5 的两条限额），纯 JVM。 */
class IMCallOptionsGuardTest {

    @Test
    fun `空群号与空 user_data 都合规`() {
        assertNull(IMCallOptionsGuard.validate("", ""))
    }

    @Test
    fun `chat_group_id 恰好 64 字节合规,65 字节不合规`() {
        assertNull(IMCallOptionsGuard.validate("a".repeat(64), ""))
        assertNotNull(IMCallOptionsGuard.validate("a".repeat(65), ""))
    }

    @Test
    fun `chat_group_id 含空白或换行不合规`() {
        assertNotNull(IMCallOptionsGuard.validate("g 42", ""))
        assertNotNull(IMCallOptionsGuard.validate("g\n42", ""))
        assertNotNull(IMCallOptionsGuard.validate("g\t42", ""))
    }

    @Test
    fun `chat_group_id 按 UTF-8 字节数算,不是字符数`() {
        // 一个中文字符在 UTF-8 下是 3 字节：22 个字符已经 66 字节，超限。
        assertNotNull(IMCallOptionsGuard.validate("群".repeat(22), ""))
        assertNull(IMCallOptionsGuard.validate("群".repeat(21), ""))
    }

    @Test
    fun `user_data 恰好 4096 字节合规,4097 字节不合规`() {
        assertNull(IMCallOptionsGuard.validate("", "a".repeat(4096)))
        assertNotNull(IMCallOptionsGuard.validate("", "a".repeat(4097)))
    }
}
