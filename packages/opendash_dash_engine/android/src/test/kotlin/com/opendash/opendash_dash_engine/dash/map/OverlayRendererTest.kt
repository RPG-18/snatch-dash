package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayRendererTest {
    @Test
    fun `rider arrow grows by 50 percent and follows camera tilt`() {
        assertEquals(1.5f, RIDER_ARROW_SCALE)
        assertEquals(1.0f, riderArrowForwardScale(0.0), 0.0001f)
        assertEquals(sqrt(0.5).toFloat(), riderArrowForwardScale(45.0), 0.0001f)
    }
}
