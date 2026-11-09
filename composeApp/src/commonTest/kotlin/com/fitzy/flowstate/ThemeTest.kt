package com.fitzy.flowstate

import androidx.compose.ui.graphics.Color
import com.fitzy.flowstate.theme.FlowColors
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeTest {
    @Test
    fun accentIsCinemaAmber() {
        assertEquals(Color(0xFFE9A23B), FlowColors.Accent)
    }
}
