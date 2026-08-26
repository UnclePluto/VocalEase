package com.vocaease.patient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildContractTest {
    @Test
    fun `构建契约提供安卓10最低版本`() {
        assertEquals(29, BuildConfig.MIN_SUPPORTED_API)
    }

    @Test
    fun `调试构建提供非空接口地址`() {
        assertTrue(BuildConfig.API_BASE_URL.isNotBlank())
    }
}
