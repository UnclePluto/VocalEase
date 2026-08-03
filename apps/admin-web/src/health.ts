export type DependencyState = "up" | "down";

export interface HealthReport {
  status: "healthy" | "degraded";
  dependencies: {
    database: DependencyState;
    redis: DependencyState;
    media_storage: DependencyState;
  };
}

export async function fetchHealth(): Promise<HealthReport> {
  const response = await fetch("/api/v1/health");
  if (!response.ok) {
    throw new Error("服务健康检查失败");
  }
  return response.json() as Promise<HealthReport>;
}

