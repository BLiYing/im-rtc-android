package com.imrtc.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画质档位。**这张表要与 Web / iOS 一字不差**——三端各写一份，谁都不引谁，
 * 所以只能靠这几条断言把它们钉在一起（对应 Web 的 `videoProfile.test.ts`、
 * iOS 的 `VideoProfileTests.swift`）。
 */
class VideoProfileTest {

    @Test
    fun `默认是 720p——1080p 在九宫格里只是白烧上行带宽`() {
        assertEquals(IMVideoProfile.P720, IMVideoProfile.DEFAULT)
    }

    @Test
    fun `720p 的码率与服务端 simulcast h 层的目标值一致`() {
        // 这个数字要和服务端 internal/sfu/bwe.go 的 bitrateHigh 对得上：
        // 那边拿它做带宽预算，对不上的话降层判断是按一个错的数字做的。
        assertEquals(1_500_000, IMVideoProfile.P720.maxBitrateBps)
        assertEquals(500_000, IMVideoProfile.P360.maxBitrateBps)
        assertEquals(3_000_000, IMVideoProfile.P1080.maxBitrateBps)
    }

    @Test
    fun `三层的 rid 与码率折算`() {
        val layers = IMVideoProfile.P720.simulcastLayers
        assertEquals(listOf("l", "m", "h"), layers.map { it.rid })
        // 顺序必须是缩放倍数从大到小：反了的话 native 侧会重排，rid 与分辨率对不上号。
        assertEquals(listOf(4.0, 2.0, 1.0), layers.map { it.scaleDownBy })
        assertEquals(150_000, layers[0].bitrateBps)
        assertEquals(500_000, layers[1].bitrateBps)
        assertEquals(1_500_000, layers[2].bitrateBps)
    }

    @Test
    fun `预设按分辨率从低到高，界面直接照着列`() {
        assertEquals(listOf("360p", "720p", "1080p"), IMVideoProfile.PRESETS.map { it.name })
    }
}
