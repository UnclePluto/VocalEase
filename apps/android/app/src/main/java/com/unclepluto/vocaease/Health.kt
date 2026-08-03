package com.unclepluto.vocaease

enum class HealthStatus {
    HEALTHY,
    DEGRADED,
    ;

    companion object {
        fun fromWireValue(value: String): HealthStatus = when (value) {
            "healthy" -> HEALTHY
            "degraded" -> DEGRADED
            else -> error("未知健康状态：$value")
        }
    }
}

enum class Availability {
    UP,
    DOWN,
    ;

    companion object {
        fun fromWireValue(value: String): Availability = when (value) {
            "up" -> UP
            "down" -> DOWN
            else -> error("未知依赖状态：$value")
        }
    }
}

data class HealthReport(
    val status: HealthStatus,
    val dependencies: Map<String, Availability>,
)

fun interface HealthGateway {
    fun fetch(): HealthReport
}

data class HealthDependency(
    val label: String,
    val available: Boolean,
)

data class HealthScreenState(
    val title: String,
    val dependencies: List<HealthDependency>,
)

class HealthScreenPresenter(
    private val gateway: HealthGateway,
) {
    fun load(): HealthScreenState {
        val report = gateway.fetch()
        return HealthScreenState(
            title = if (report.status == HealthStatus.HEALTHY) {
                "服务运行正常"
            } else {
                "部分服务不可用"
            },
            dependencies = DEPENDENCY_LABELS.map { (key, label) ->
                HealthDependency(label, report.dependencies[key] == Availability.UP)
            },
        )
    }

    private companion object {
        val DEPENDENCY_LABELS = listOf(
            "database" to "PostgreSQL",
            "redis" to "Redis",
            "media_storage" to "媒体存储",
        )
    }
}
