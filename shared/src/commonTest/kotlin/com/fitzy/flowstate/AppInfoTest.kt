package com.fitzy.flowstate

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun packageNameMatchesProject() {
        assertEquals("com.fitzy.flowstate", AppInfo.PACKAGE)
    }
}
