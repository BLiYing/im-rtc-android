package com.imrtc.uikit

import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宿主身份解析（[IMProfileResolver]）。
 *
 * 最要紧的一条是**同一个 uid 在不同解析器下显示不同的名字**——那正是备注的语义，
 * 也正是这份信息不能走服务端广播、必须由宿主本机解析的原因。
 *
 * 这里测的是解析口径本身（纯逻辑），不碰 View——画得对不对属于真机验收。
 */
class ProfileResolverTest {

    /**
     * 造一个假解析器。**头像一律返回 null**：`Drawable` 在纯 JVM 单测里造不出真实例
     * （android.jar 是桩），而这一层要验的是「解析口径」，不是画得对不对。
     * 头像真正显示成什么样属于真机验收。
     */
    private fun resolver(names: Map<String, String?>): IMProfileResolver =
        object : IMProfileResolver {
            override fun displayName(uid: String): String? = names[uid]
            override fun avatar(uid: String): Drawable? = null
        }

    @Test
    fun `没配 resolver 时原样用兜底 —— 与加钩子之前一致`() {
        assertEquals("4820571639", resolvedName(null, "4820571639", "4820571639"))
        assertNull(resolvedAvatar(null, "4820571639"))
    }

    @Test
    fun `配了就显示宿主给的名字`() {
        val r = resolver(mapOf("4820571639" to "明子"))
        assertEquals("明子", resolvedName(r, "4820571639", "4820571639"))
    }

    /*
      这一条是整件事的立足点：同一个 uid，两台设备两个名字（各自的备注）。
      如果显示名走服务端广播，这个用例根本不可能通过。
    */
    @Test
    fun `同一个 uid 在两个解析器下显示不同的名字`() {
        val deviceA = resolver(mapOf("u1" to "老张（欠我钱）"))
        val deviceB = resolver(mapOf("u1" to "张经理"))
        assertEquals("老张（欠我钱）", resolvedName(deviceA, "u1", "u1"))
        assertEquals("张经理", resolvedName(deviceB, "u1", "u1"))
    }

    /*
      「查到了但名字是空的」比「没查到」更糟：直接用会让格子上什么都没有。
    */
    @Test
    fun `名字是 null 或空白时退回兜底`() {
        val r = resolver(mapOf("a" to null, "b" to "", "c" to "   "))
        assertEquals("a", resolvedName(r, "a", "a"))
        assertEquals("b", resolvedName(r, "b", "b"))
        assertEquals("c", resolvedName(r, "c", "c"))
    }

    /*
      本端那格 uid 是空串，label 就是「我」。去解析它既没意义、也会让宿主
      收到一个莫名其妙的空 uid 查询。
    */
    @Test
    fun `本端那格（uid 为空）不解析，直接用兜底`() {
        val r = resolver(mapOf("" to "不该被用到"))
        assertEquals("我", resolvedName(r, "", "我"))
        assertNull(resolvedAvatar(r, ""))
    }

    @Test
    fun `没有头像时返回 null，调用方退化成首字母色块`() {
        assertNull(resolvedAvatar(resolver(emptyMap()), "u1"))
    }
}
