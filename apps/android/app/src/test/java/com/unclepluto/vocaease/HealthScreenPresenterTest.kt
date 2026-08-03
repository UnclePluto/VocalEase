package com.unclepluto.vocaease

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthScreenPresenterTest {
    @Test
    fun `participant sees the shared service and every dependency as available`() {
        val gateway = HealthGateway {
            HealthReport(
                status = "healthy",
                dependencies = mapOf(
                    "database" to "up",
                    "redis" to "up",
                    "media_storage" to "up",
                ),
            )
        }

        val state = HealthScreenPresenter(gateway).load()

        assertEquals("服务运行正常", state.title)
        assertEquals(
            listOf(
                HealthDependency("PostgreSQL", true),
                HealthDependency("Redis", true),
                HealthDependency("媒体存储", true),
            ),
            state.dependencies,
        )
    }
}
