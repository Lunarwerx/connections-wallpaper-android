package com.anthonyla.paperize.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VignetteTest {
    private val width = 1080f
    private val height = 2400f

    @Test
    fun `the band is as thick from the top as from the left`() {
        val fromTop = vignetteDarkness(width / 2, 60f, width, height, 50)
        val fromLeft = vignetteDarkness(60f, height / 2, width, height, 50)
        assertEquals(fromLeft, fromTop, 0.0001f)
        assertTrue(fromTop > 0f)
    }

    @Test
    fun `the centre is not darkened`() {
        assertEquals(0f, vignetteDarkness(width / 2, height / 2, width, height, 50), 0f)
    }

    @Test
    fun `a corner is darker than an edge midpoint`() {
        val corner = vignetteDarkness(0f, 0f, width, height, 50)
        val edge = vignetteDarkness(0f, height / 2, width, height, 50)
        assertTrue(corner > edge)
    }
}
