package com.unclepluto.vocaease

data class HealthReport(
    val status: String,
    val dependencies: Map<String, String>,
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
            title = if (report.status == "healthy") "服务运行正常" else "服务暂时不可用",
            dependencies = DEPENDENCY_LABELS.map { (key, label) ->
                HealthDependency(label, report.dependencies[key] == "up")
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
