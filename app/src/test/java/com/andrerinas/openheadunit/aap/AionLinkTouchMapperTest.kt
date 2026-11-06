package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AionLinkTouchMapperTest {
    @Test fun scalesPointerAndPreservesIndex() {
        val result = AionLinkTouchMapper.map(5 or (1 shl 8), intArrayOf(2, 7),
            intArrayOf(960, 0), intArrayOf(480, 959), 1920, 960, 1920, 1080)!!
        assertEquals(5, result.action)
        assertEquals(1, result.index)
        assertEquals(listOf(Triple(2, 960, 540), Triple(7, 0, 1078)), result.pointers)
    }

    @Test fun rejectsMalformedPointersAndActions() {
        fun map(raw: Int, ids: IntArray, xs: IntArray, ys: IntArray) =
            AionLinkTouchMapper.map(raw, ids, xs, ys, 1920, 960, 1920, 1080)
        assertNull(map(3, intArrayOf(0), intArrayOf(1), intArrayOf(1)))
        assertNull(map(0, intArrayOf(0), intArrayOf(), intArrayOf(1)))
        assertNull(map(5 or (2 shl 8), intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(1, 2)))
        assertNull(map(2, intArrayOf(0, 0), intArrayOf(1, 2), intArrayOf(1, 2)))
        assertNull(map(0, intArrayOf(0), intArrayOf(1920), intArrayOf(1)))
        assertNull(AionLinkTouchMapper.map(0, intArrayOf(0), intArrayOf(1), intArrayOf(1), 0, 960, 1920, 1080))
    }
}
