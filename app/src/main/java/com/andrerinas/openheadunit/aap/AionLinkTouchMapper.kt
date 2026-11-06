package com.andrerinas.openheadunit.aap

/** Validates bridge input before converting coordinates to the negotiated video dimensions. */
internal object AionLinkTouchMapper {
    data class Mapped(val action: Int, val index: Int, val pointers: List<Triple<Int, Int, Int>>)

    fun map(raw: Int, ids: IntArray, xs: IntArray, ys: IntArray,
            width: Int, height: Int, videoWidth: Int, videoHeight: Int): Mapped? {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
        if (ids.size !in 1..10 || ids.size != xs.size || ids.size != ys.size) return null
        if (ids.any { it < 0 } || ids.toSet().size != ids.size) return null
        val action = raw and 0xff
        if (action !in setOf(0, 1, 2, 5, 6) || raw and 0xffff0000.toInt() != 0) return null
        val index = (raw ushr 8) and 0xff
        if (index >= ids.size || (action !in setOf(5, 6) && index != 0)) return null
        if (action in setOf(5, 6) && ids.size < 2) return null
        val pointers = ids.indices.map { i ->
            if (xs[i] !in 0 until width || ys[i] !in 0 until height) return null
            Triple(ids[i], (xs[i].toLong() * videoWidth / width).toInt(),
                (ys[i].toLong() * videoHeight / height).toInt())
        }
        return Mapped(action, index, pointers)
    }
}
