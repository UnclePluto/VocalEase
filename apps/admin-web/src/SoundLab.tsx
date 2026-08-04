import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Card, List, Modal, Space, Tag } from "antd";

import {
  createPlaybackMixAccess,
  deleteSingingSession,
  getPlaybackMix,
  getSoundLab,
  listSingingSessionSummaries,
  loadProtectedMedia,
  type LabQualityReport,
  type PlaybackMix,
  type SingingSessionSummary,
  type SoundLabData
} from "./admin-api";
import { useProtectedMedia } from "./protected-media";

interface QualityMarker {
  kind: string;
  start_ms: number;
  end_ms: number;
  value?: number;
}

export function SoundLab({
  token,
  onError
}: {
  token: string;
  onError: (message: string) => void;
}) {
  const [summaries, setSummaries] = useState<SingingSessionSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [lab, setLab] = useState<SoundLabData>();
  const [loading, setLoading] = useState(false);

  const refresh = async () => {
    try {
      setLoading(true);
      const items = await listSingingSessionSummaries(token);
      setSummaries(items);
      if (selectedId && !items.some((item) => item.id === selectedId)) {
        setSelectedId(undefined);
        setLab(undefined);
      }
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "演唱会话加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, [token]);

  useEffect(() => {
    if (!selectedId) return;
    let active = true;
    setLoading(true);
    void getSoundLab(token, selectedId)
      .then((result) => {
        if (active) {
          setLab(result);
          onError("");
        }
      })
      .catch((reason) => {
        if (active) onError(reason instanceof Error ? reason.message : "声音实验室加载失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [onError, selectedId, token]);

  return (
    <div className="sound-lab-layout">
      <aside className="session-sidebar" aria-label="演唱会话列表">
        <div className="lab-section-title">
          <div>
            <p className="eyebrow">SESSION ARCHIVE</p>
            <h2>演唱会话</h2>
          </div>
          <Button loading={loading} onClick={() => void refresh()}>
            刷新
          </Button>
        </div>
        <List
          locale={{ emptyText: "暂无可查看的演唱会话。" }}
          dataSource={summaries}
          renderItem={(session) => (
            <List.Item>
              <button
                type="button"
                className={
                  selectedId === session.id
                    ? "session-nav session-nav-active"
                    : "session-nav"
                }
                onClick={() => setSelectedId(session.id)}
              >
                <strong>{session.song_title}</strong>
                <span>{session.participant_research_code}</span>
                <span className="session-status-row">
                  <Tag color={session.quality_status === "warning" ? "gold" : "green"}>
                    质量 {session.quality_status ?? "待生成"}
                  </Tag>
                  <Tag color={session.used_headphones ? "cyan" : "volcano"}>
                    {session.used_headphones ? "耳机" : "无耳机"}
                  </Tag>
                </span>
              </button>
            </List.Item>
          )}
        />
      </aside>
      <section className="lab-workspace">
        {lab && lab.singing_session_id === selectedId ? (
          <LabWorkspace
            token={token}
            lab={lab}
            onDeleted={async () => {
              setSelectedId(undefined);
              setLab(undefined);
              await refresh();
            }}
            onError={onError}
          />
        ) : (
          <Card className="lab-empty">
            <div className="lab-radar" aria-hidden="true" />
            <h2>{loading ? "正在读取信号…" : "选择一条演唱会话"}</h2>
            <p>这里仅展示录音技术质量和采集环境，不提供健康、诊断或唱功评分。</p>
          </Card>
        )}
      </section>
    </div>
  );
}

function LabWorkspace({
  token,
  lab,
  onDeleted,
  onError
}: {
  token: string;
  lab: SoundLabData;
  onDeleted: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [cursorMs, setCursorMs] = useState(0);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [mix, setMix] = useState<PlaybackMix>();
  const [mixUrl, setMixUrl] = useState<string>();
  const [mixLoading, setMixLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const rawVoiceSrc = useProtectedMedia(token, lab.raw_voice_url);
  const spectrogramSrc = useProtectedMedia(token, lab.spectrogram_url);
  const rawAudio = useRef<HTMLAudioElement>(null);
  const markers = useMemo(() => qualityMarkers(lab.quality_reports), [lab.quality_reports]);
  const warnings = useMemo(() => qualityWarnings(lab.quality_reports), [lab.quality_reports]);

  const jumpTo = (startMs: number) => {
    setCursorMs(startMs);
    if (rawAudio.current) rawAudio.current.currentTime = startMs / 1000;
  };

  const requestMix = async () => {
    try {
      setMixLoading(true);
      const status = await getPlaybackMix(token, lab.singing_session_id);
      setMix(status);
      if (status.status === "succeeded" && status.media_ready) {
        const access = await createPlaybackMixAccess(token, lab.singing_session_id);
        setMixUrl(access.url);
      }
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "体验回放加载失败");
    } finally {
      setMixLoading(false);
    }
  };

  return (
    <div className="lab-console">
      <header className="lab-console-header">
        <div>
          <p className="eyebrow">SIGNAL ANALYSIS · {lab.participant_research_code}</p>
          <h2>
            {lab.song_title} <small>/ {lab.song_artist}</small>
          </h2>
        </div>
        <Space wrap>
          <Tag color={warnings.length > 0 ? "gold" : "green"}>
            {warnings.length > 0 ? `${warnings.length} 项技术警告` : "技术质量正常"}
          </Tag>
          <Button danger onClick={() => setDeleteOpen(true)}>
            删除本次录音
          </Button>
        </Space>
      </header>

      <Alert
        showIcon
        type="info"
        title="研究录音技术视图"
        description="波形、频谱和标记仅反映文件及采集质量，不代表健康状况、疾病诊断或演唱水平。"
      />

      <section className="signal-panel">
        <div className="signal-panel-heading">
          <h3>原始人声波形</h3>
          <span>{formatTime(cursorMs)} / {formatTime(lab.stages.post_end_ms)}</span>
        </div>
        <WaveformTimeline
          waveform={lab.waveform}
          durationMs={lab.stages.post_end_ms}
          stages={lab.stages}
          markers={markers}
          cursorMs={cursorMs}
        />
        <div className="stage-legend" aria-label="录制阶段">
          <span><i className="legend-pre" />唱前</span>
          <span><i className="legend-singing" />唱中</span>
          <span><i className="legend-post" />唱后</span>
        </div>
        {rawVoiceSrc ? (
          <audio
            ref={rawAudio}
            aria-label="原始人声轨"
            controls
            src={rawVoiceSrc}
            onTimeUpdate={(event) => setCursorMs(event.currentTarget.currentTime * 1000)}
          />
        ) : (
          <p className="muted">正在鉴权加载原始人声…</p>
        )}
        <Space wrap>
          <Button
            loading={downloading}
            onClick={() => {
              setDownloading(true);
              void downloadRawVoice(token, lab)
                .then(() => onError(""))
                .catch((reason) => {
                  onError(reason instanceof Error ? reason.message : "原始人声下载失败");
                })
                .finally(() => setDownloading(false));
            }}
          >
            下载原始人声 WAV
          </Button>
          <span className="muted">下载操作会写入审计日志。</span>
        </Space>
      </section>

      <section className="signal-panel">
        <div className="signal-panel-heading">
          <h3>真实音频频谱</h3>
          <Tag color="cyan">受保护媒体</Tag>
        </div>
        {spectrogramSrc ? (
          <img
            className="spectrogram"
            src={spectrogramSrc}
            alt={`${lab.song_title}原始人声频谱图`}
          />
        ) : (
          <div className="spectrogram-placeholder">正在鉴权加载频谱…</div>
        )}
      </section>

      <div className="lab-detail-grid">
        <section className="signal-panel">
          <h3>质量标记与文件警告</h3>
          {warnings.length > 0 ? (
            <ul className="warning-list">
              {warnings.map((warning, index) => <li key={`${warning}-${index}`}>{warning}</li>)}
            </ul>
          ) : (
            <p className="muted">没有文件级技术警告。</p>
          )}
          <div className="marker-list">
            {markers.map((marker, index) => (
              <button
                key={`${marker.kind}-${marker.start_ms}-${index}`}
                type="button"
                onClick={() => jumpTo(marker.start_ms)}
              >
                <strong>{markerLabel(marker.kind)}</strong>
                <span>{formatTime(marker.start_ms)}–{formatTime(marker.end_ms)}</span>
              </button>
            ))}
            {markers.length === 0 && <p className="muted">没有时间段质量标记。</p>}
          </div>
          <QualityFacts reports={lab.quality_reports} />
        </section>

        <section className="signal-panel">
          <h3>采集环境快照</h3>
          <dl className="device-snapshot">
            {Object.entries(lab.device_snapshot).map(([key, value]) => (
              <div key={key}>
                <dt>{snapshotLabel(key)}</dt>
                <dd>{formatSnapshotValue(value)}</dd>
              </div>
            ))}
          </dl>
          <p className="muted">
            伴奏起始帧：{lab.accompaniment_start_frame ?? "未记录"}（48 kHz 时间基准）
          </p>
        </section>
      </div>

      <section className="signal-panel experience-panel">
        <div>
          <h3>回放混音</h3>
          <p>
            这是原始人声与伴奏对齐生成的体验文件，只用于回听，不替代原始研究录音。
          </p>
        </div>
        <Button
          disabled={!lab.playback_mix_status}
          loading={mixLoading}
          onClick={() => void requestMix()}
        >
          获取体验回放
        </Button>
        {mix && mix.status !== "succeeded" && (
          <Tag color={mix.status === "failed" ? "red" : "processing"}>
            混音状态：{mix.failure_message ?? mix.status}
          </Tag>
        )}
        {mixUrl && (
          <audio aria-label="体验回放混音" controls src={mixUrl} />
        )}
      </section>

      <Modal
        open={deleteOpen}
        title="再次确认删除本次录音"
        okText="永久删除录音"
        cancelText="取消"
        confirmLoading={deleting}
        okButtonProps={{ danger: true }}
        onCancel={() => setDeleteOpen(false)}
        onOk={() => {
          setDeleting(true);
          void deleteSingingSession(token, lab.singing_session_id)
            .then(onDeleted)
            .catch((reason) => {
              onError(reason instanceof Error ? reason.message : "录音删除失败");
            })
            .finally(() => setDeleting(false));
        }}
      >
        <p>
          删除后将清除原始人声、体验混音、波形、频谱、质量记录及上传缓存，无法恢复。
        </p>
      </Modal>
    </div>
  );
}

export function WaveformTimeline({
  waveform,
  durationMs,
  stages,
  markers,
  cursorMs
}: {
  waveform: SoundLabData["waveform"];
  durationMs: number;
  stages: SoundLabData["stages"];
  markers: QualityMarker[];
  cursorMs: number;
}) {
  const width = 1000;
  const height = 240;
  const safeDuration = Math.max(1, durationMs);
  const x = (milliseconds: number) => Math.min(width, Math.max(0, milliseconds / safeDuration * width));
  return (
    <svg
      className="waveform-timeline"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={`真实波形，共 ${waveform.length} 个采样包络点`}
      tabIndex={0}
    >
      <rect x="0" y="0" width={x(stages.singing_start_ms)} height={height} className="stage-pre" />
      <rect
        x={x(stages.singing_start_ms)}
        y="0"
        width={x(stages.singing_end_ms) - x(stages.singing_start_ms)}
        height={height}
        className="stage-singing"
      />
      <rect
        x={x(stages.singing_end_ms)}
        y="0"
        width={x(stages.post_end_ms) - x(stages.singing_end_ms)}
        height={height}
        className="stage-post"
      />
      <line x1="0" x2={width} y1={height / 2} y2={height / 2} className="waveform-zero" />
      {waveform.map((point, index) => (
        <line
          key={`${point.start_ms}-${index}`}
          data-waveform-point="true"
          x1={x(point.start_ms)}
          x2={x(point.start_ms)}
          y1={(1 - Math.max(-1, Math.min(1, point.max))) * height / 2}
          y2={(1 - Math.max(-1, Math.min(1, point.min))) * height / 2}
          className="waveform-sample"
        />
      ))}
      {markers.map((marker, index) => (
        <rect
          key={`${marker.kind}-${marker.start_ms}-${index}`}
          data-quality-marker={marker.kind}
          x={x(marker.start_ms)}
          y="0"
          width={Math.max(2, x(marker.end_ms) - x(marker.start_ms))}
          height={height}
          className={`quality-marker quality-marker-${marker.kind}`}
        >
          <title>{markerLabel(marker.kind)} {formatTime(marker.start_ms)}</title>
        </rect>
      ))}
      {[stages.singing_start_ms, stages.singing_end_ms].map((boundary) => (
        <line
          key={boundary}
          data-stage-boundary="true"
          x1={x(boundary)}
          x2={x(boundary)}
          y1="0"
          y2={height}
          className="stage-boundary"
        />
      ))}
      <line
        data-playback-cursor="true"
        x1={x(cursorMs)}
        x2={x(cursorMs)}
        y1="0"
        y2={height}
        className="playback-cursor"
      />
    </svg>
  );
}

function qualityMarkers(reports: LabQualityReport[]): QualityMarker[] {
  return reports.flatMap((report) => {
    const markers = report.metrics.markers;
    if (!Array.isArray(markers)) return [];
    return markers.filter(
      (marker): marker is QualityMarker =>
        Boolean(
          marker &&
            typeof marker === "object" &&
            typeof (marker as QualityMarker).kind === "string" &&
            typeof (marker as QualityMarker).start_ms === "number" &&
            typeof (marker as QualityMarker).end_ms === "number"
        )
    );
  });
}

function qualityWarnings(reports: LabQualityReport[]): string[] {
  return [
    ...new Set(
      reports.flatMap((report) => {
        const warnings = report.metrics.file_warnings;
        return Array.isArray(warnings)
          ? warnings.filter((warning): warning is string => typeof warning === "string")
          : [];
      })
    )
  ];
}

function QualityFacts({ reports }: { reports: LabQualityReport[] }) {
  const report = reports.at(-1);
  if (!report) return null;
  const keys = [
    ["sample_rate", "采样率"],
    ["channels", "声道数"],
    ["bit_depth", "位深"],
    ["duration_ms", "文件时长"],
    ["rms_dbfs", "RMS dBFS"],
    ["silent_sample_ratio", "静音采样比例"],
    ["clipped_sample_ratio", "削波采样比例"]
  ] as const;
  return (
    <dl className="quality-facts">
      {keys.map(([key, label]) => (
        <div key={key}>
          <dt>{label}</dt>
          <dd>{formatMetric(key, report.metrics[key])}</dd>
        </div>
      ))}
    </dl>
  );
}

function formatMetric(key: string, value: unknown): string {
  if (typeof value !== "number") return "—";
  if (key === "sample_rate") return `${value} Hz`;
  if (key === "bit_depth") return `${value} bit`;
  if (key === "duration_ms") return formatTime(value);
  if (key.endsWith("_ratio")) return `${(value * 100).toFixed(2)}%`;
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

function markerLabel(kind: string): string {
  return {
    silence: "静音区间",
    low_volume: "低音量区间",
    clipping: "削波区间"
  }[kind] ?? `技术标记：${kind}`;
}

function snapshotLabel(key: string): string {
  return {
    manufacturer: "制造商",
    model: "设备型号",
    android_version: "Android 版本",
    app_version: "应用版本",
    input_type: "输入设备",
    output_route: "输出路由",
    bluetooth_mode: "蓝牙模式",
    sample_rate: "录制采样率",
    channels: "录制声道数",
    bit_depth: "录制位深"
  }[key] ?? key;
}

function formatSnapshotValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "无";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function formatTime(milliseconds: number): string {
  const seconds = Math.max(0, milliseconds) / 1000;
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${(seconds % 60).toFixed(1).padStart(4, "0")}`;
}

async function downloadRawVoice(token: string, lab: SoundLabData): Promise<void> {
  const separator = lab.raw_voice_url.includes("?") ? "&" : "?";
  const blob = await loadProtectedMedia(
    token,
    `${lab.raw_voice_url}${separator}download=true`
  );
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.download = `raw-voice-${lab.singing_session_id}.wav`;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(href);
}
