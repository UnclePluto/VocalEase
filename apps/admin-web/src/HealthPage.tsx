import { useEffect, useState } from "react";

import { fetchHealth, type HealthReport } from "./health";

const dependencyLabels: Record<keyof HealthReport["dependencies"], string> = {
  database: "PostgreSQL",
  redis: "Redis",
  media_storage: "媒体存储"
};

export function HealthPage() {
  const [report, setReport] = useState<HealthReport>();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    fetchHealth()
      .then(setReport)
      .catch(() => setFailed(true));
  }, []);

  if (failed) {
    return (
      <main className="health-shell">
        <section className="health-panel" aria-live="polite">
          <p className="eyebrow">VOCAEASE RESEARCH OS</p>
          <h1>服务暂不可用</h1>
          <p className="summary">无法连接共用业务服务，请检查本地开发环境。</p>
        </section>
      </main>
    );
  }

  if (!report) {
    return (
      <main className="health-shell">
        <section className="health-panel" aria-live="polite">
          <p className="eyebrow">VOCAEASE RESEARCH OS</p>
          <h1>正在检查服务</h1>
        </section>
      </main>
    );
  }

  return (
    <main className="health-shell">
      <section className="health-panel">
        <p className="eyebrow">VOCAEASE RESEARCH OS</p>
        <h1>{report.status === "healthy" ? "服务运行正常" : "部分服务不可用"}</h1>
        <p className="summary">Web 管理后台已连接共用业务服务。</p>
        <dl className="dependency-list">
          {Object.entries(report.dependencies).map(([name, state]) => (
            <div className="dependency-row" key={name}>
              <dt>{dependencyLabels[name as keyof HealthReport["dependencies"]]}</dt>
              <dd data-state={state}>{state === "up" ? "可用" : "不可用"}</dd>
            </div>
          ))}
        </dl>
      </section>
    </main>
  );
}

