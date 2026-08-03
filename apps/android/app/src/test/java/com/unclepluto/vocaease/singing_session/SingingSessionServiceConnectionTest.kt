package com.unclepluto.vocaease.singing_session

import com.unclepluto.vocaease.Availability
import com.unclepluto.vocaease.HealthDependency
import com.unclepluto.vocaease.HealthGateway
import com.unclepluto.vocaease.HealthReport
import com.unclepluto.vocaease.HealthScreenPresenter
import com.unclepluto.vocaease.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SingingSessionServiceConnectionTest {
    @Test
    fun `participant sees the shared service and every dependency as available`() {
        val gateway = HealthGateway {
            HealthReport(
                status = HealthStatus.HEALTHY,
                dependencies = mapOf(
                    "database" to Availability.UP,
                    "redis" to Availability.UP,
                    "media_storage" to Availability.UP,
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

    @Test
    fun `participant sees which dependency is unavailable when service is degraded`() {
        val gateway = HealthGateway {
            HealthReport(
                status = HealthStatus.DEGRADED,
                dependencies = mapOf(
                    "database" to Availability.UP,
                    "redis" to Availability.DOWN,
                    "media_storage" to Availability.UP,
                ),
            )
        }

        val state = HealthScreenPresenter(gateway).load()

        assertEquals("部分服务不可用", state.title)
        assertEquals(HealthDependency("Redis", false), state.dependencies[1])
    }
}
