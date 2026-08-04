import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  ConfigProvider,
  Form,
  Input,
  List,
  Modal,
  Radio,
  Select,
  Space,
  Tabs,
  Tag,
  theme
} from "antd";

import {
  acceptSeparation,
  createParticipant,
  createSeparation,
  createSong,
  deleteParticipant,
  getSeparation,
  listAuditEvents,
  listAdminSongs,
  listParticipants,
  listSeparations,
  loginAdmin,
  publishSong,
  rejectSeparation,
  resetParticipantPassword,
  retrySeparation,
  saveLyrics,
  setParticipantActive,
  unpublishSong,
  uploadBackingTrack,
  uploadSongCover,
  updateParticipant,
  type AuditEvent,
  type AuditFilters,
  type AdminBackingTrack,
  type AdminSong,
  type Participant,
  type SeparationJob
} from "./admin-api";
import { LrcPreview } from "./LrcPreview";
import { useProtectedMedia } from "./protected-media";
import { SoundLab } from "./SoundLab";

export function AdminApp() {
  const [token, setToken] = useState<string>();
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [songs, setSongs] = useState<AdminSong[]>([]);
  const [selectedSongId, setSelectedSongId] = useState<string>();
  const [error, setError] = useState("");

  if (!token) {
    return (
      <AdminShell title="管理员登录">
        <LoginForm
          error={error}
          onLogin={async (username, password) => {
            try {
              const accessToken = await loginAdmin(username, password);
              const [participantList, songList] = await Promise.all([
                listParticipants(accessToken),
                listAdminSongs(accessToken)
              ]);
              setToken(accessToken);
              setParticipants(participantList);
              setSongs(songList);
              setSelectedSongId(songList[0]?.id);
              setError("");
            } catch (reason) {
              setError(reason instanceof Error ? reason.message : "登录失败");
            }
          }}
        />
      </AdminShell>
    );
  }

  const selectedSong = songs.find((song) => song.id === selectedSongId);
  const replaceSong = (updated: AdminSong) => {
    setSongs((items) => items.map((item) => (item.id === updated.id ? updated : item)));
  };

  return (
    <AdminShell title="研究管理后台">
      {error && <Alert className="global-alert" message={error} type="error" closable />}
      <Tabs
        defaultActiveKey="songs"
        items={[
          {
            key: "songs",
            label: "研究曲库",
            children: (
              <SongManagement
                token={token}
                songs={songs}
                selectedSong={selectedSong}
                onSelect={setSelectedSongId}
                onError={setError}
                onCreate={(song) => {
                  setSongs((items) => [...items, song]);
                  setSelectedSongId(song.id);
                }}
                onUpdate={replaceSong}
              />
            )
          },
          {
            key: "participants",
            label: "参与者",
            children: (
              <ParticipantManagement
                token={token}
                participants={participants}
                error={error}
                onParticipantsChange={setParticipants}
                onError={setError}
              />
            )
          },
          {
            key: "audit",
            label: "审计日志",
            children: <AuditLog token={token} onError={setError} />
          },
          {
            key: "sound-lab",
            label: "声音实验室",
            children: <SoundLab token={token} onError={setError} />
          }
        ]}
      />
    </AdminShell>
  );
}

function ParticipantManagement({
  token,
  participants,
  error,
  onParticipantsChange,
  onError
}: {
  token: string;
  participants: Participant[];
  error: string;
  onParticipantsChange: (participants: Participant[]) => void;
  onError: (error: string) => void;
}) {
  const [editing, setEditing] = useState<Participant>();
  const [deleteTarget, setDeleteTarget] = useState<Participant>();
  const [deleteSingingData, setDeleteSingingData] = useState<boolean>();
  const [deleting, setDeleting] = useState(false);
  const [searching, setSearching] = useState(false);

  const updateItem = (updated: Participant) => {
    onParticipantsChange(
      participants.map((participant) =>
        participant.id === updated.id ? updated : participant
      )
    );
  };

  const run = async (action: () => Promise<void>) => {
    try {
      await action();
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "参与者操作失败");
    }
  };

  return (
    <>
      <Input.Search
        allowClear
        aria-label="搜索参与者"
        autoComplete="off"
        className="participant-search"
        enterButton="搜索"
        name="participant-search"
        placeholder="按姓名、手机号或研究编号搜索"
        onSearch={(query) => {
          setSearching(true);
          void run(async () => {
            onParticipantsChange(await listParticipants(token, query));
          }).finally(() => setSearching(false));
        }}
        loading={searching}
      />
      <ParticipantForm
        error={error}
        onCreate={async (participant) => {
          try {
            const created = await createParticipant(token, participant);
            onParticipantsChange([...participants, created]);
            onError("");
          } catch (reason) {
            onError(reason instanceof Error ? reason.message : "创建失败");
          }
        }}
      />
      <List
        className="participant-grid"
        grid={{ gutter: 16, xs: 1, sm: 2, lg: 3 }}
        dataSource={participants}
        renderItem={(participant) => (
          <List.Item>
            <Card title={participant.name}>
              <p>{participant.research_code}</p>
              <p>{participant.phone}</p>
              <Space wrap>
                <Tag color={participant.active ? "green" : "red"}>
                  {participant.active ? "账号启用" : "账号停用"}
                </Tag>
                <Tag color={participant.must_change_password ? "gold" : "blue"}>
                  {participant.must_change_password ? "初始密码待修改" : "密码已更新"}
                </Tag>
              </Space>
              <div className="participant-actions">
                <Button
                  onClick={() =>
                    void run(async () => {
                      updateItem(
                        await setParticipantActive(token, participant.id, !participant.active)
                      );
                    })
                  }
                >
                  {participant.active ? "停用" : "恢复启用"}
                </Button>
                <Button
                  onClick={() =>
                    void run(async () => {
                      await resetParticipantPassword(token, participant.id);
                      onParticipantsChange(
                        participants.map((item) =>
                          item.id === participant.id
                            ? { ...item, must_change_password: true }
                            : item
                        )
                      );
                    })
                  }
                >
                  重置为初始密码
                </Button>
                <Button onClick={() => setEditing(participant)}>编辑资料</Button>
                <Button
                  danger
                  onClick={() => {
                    setDeleteTarget(participant);
                    setDeleteSingingData(undefined);
                  }}
                >
                  删除参与者
                </Button>
              </div>
            </Card>
          </List.Item>
        )}
      />
      {editing && (
        <ParticipantEditModal
          key={editing.id}
          participant={editing}
          onCancel={() => setEditing(undefined)}
          onSave={(changes) =>
            run(async () => {
              updateItem(await updateParticipant(token, editing.id, changes));
              setEditing(undefined);
            })
          }
        />
      )}
      <Modal
        open={Boolean(deleteTarget)}
        title="删除测试参与者"
        okText="确认删除参与者"
        cancelText="取消"
        confirmLoading={deleting}
        okButtonProps={{ danger: true, disabled: deleteSingingData === undefined }}
        onCancel={() => setDeleteTarget(undefined)}
        onOk={() => {
          if (!deleteTarget || deleteSingingData === undefined) return;
          setDeleting(true);
          void deleteParticipant(token, deleteTarget.id, deleteSingingData)
            .then(async () => {
              onParticipantsChange(await listParticipants(token));
              setDeleteTarget(undefined);
              onError("");
            })
            .catch((reason) => {
              onError(reason instanceof Error ? reason.message : "参与者删除失败");
            })
            .finally(() => setDeleting(false));
        }}
      >
        <p>必须明确选择是否同时删除该参与者的演唱数据。此操作无法撤销。</p>
        <Radio.Group
          aria-label="演唱数据处理方式"
          value={deleteSingingData}
          onChange={(event) => setDeleteSingingData(event.target.value as boolean)}
        >
          <Space direction="vertical">
            <Radio value={false}>保留演唱数据，仅删除参与者身份与账号</Radio>
            <Radio value={true}>同时永久删除该参与者的全部演唱数据</Radio>
          </Space>
        </Radio.Group>
      </Modal>
    </>
  );
}

function ParticipantEditModal({
  participant,
  onSave,
  onCancel
}: {
  participant: Participant;
  onSave: (
    changes: Pick<Participant, "name" | "phone" | "research_code" | "active">
  ) => Promise<void>;
  onCancel: () => void;
}) {
  const [form] = Form.useForm<
    Pick<Participant, "name" | "phone" | "research_code" | "active">
  >();
  return (
    <Modal
      open
      title={`编辑参与者 · ${participant.research_code}`}
      okText="保存修改"
      cancelText="取消"
      onCancel={onCancel}
      onOk={() => void form.submit()}
    >
      <Form
        form={form}
        name="participant-edit"
        layout="vertical"
        initialValues={{
          name: participant.name,
          phone: participant.phone,
          research_code: participant.research_code,
          active: participant.active
        }}
        onFinish={(values) => void onSave(values)}
      >
        <Form.Item label="姓名" name="name" rules={[{ required: true }]}>
          <Input autoComplete="name" />
        </Form.Item>
        <Form.Item
          label="手机号"
          name="phone"
          rules={[{ required: true, pattern: /^1\d{10}$/ }]}
        >
          <Input autoComplete="tel" inputMode="tel" />
        </Form.Item>
        <Form.Item label="研究编号" name="research_code" rules={[{ required: true }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label="账号状态" name="active" rules={[{ required: true }]}>
          <Select
            options={[
              { value: true, label: "启用" },
              { value: false, label: "停用" }
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function AuditLog({
  token,
  onError
}: {
  token: string;
  onError: (error: string) => void;
}) {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async (filters: AuditFilters = {}) => {
    try {
      setLoading(true);
      setEvents(await listAuditEvents(token, filters));
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "审计日志加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [token]);

  return (
    <section className="audit-log">
      <Form<{
        actorAccountId?: string;
        objectType?: string;
        action?: string;
        createdFrom?: string;
        createdTo?: string;
      }>
        className="audit-filters"
        layout="vertical"
        name="audit-filter"
        onFinish={(values) =>
          void load({
            actor_account_id: values.actorAccountId,
            object_type: values.objectType,
            action: values.action,
            created_from: toIsoDateTime(values.createdFrom),
            created_to: toIsoDateTime(values.createdTo)
          })
        }
      >
        <Form.Item label="操作者账号 ID" name="actorAccountId">
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label="对象类型" name="objectType">
          <Input autoComplete="off" placeholder="participant、song、media" />
        </Form.Item>
        <Form.Item label="操作" name="action">
          <Input autoComplete="off" placeholder="participant.updated" />
        </Form.Item>
        <Form.Item label="开始时间" name="createdFrom">
          <Input type="datetime-local" />
        </Form.Item>
        <Form.Item label="结束时间" name="createdTo">
          <Input type="datetime-local" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          筛选日志
        </Button>
      </Form>

      <div className="audit-table-wrap">
        <table className="audit-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作者</th>
              <th>操作</th>
              <th>对象</th>
              <th>安全摘要</th>
            </tr>
          </thead>
          <tbody>
            {events.map((event) => (
              <tr key={event.id}>
                <td>{new Date(event.created_at).toLocaleString("zh-CN")}</td>
                <td>{event.actor_account_id ?? "系统"}</td>
                <td>{event.action}</td>
                <td>
                  {event.object_type}
                  {event.object_id ? ` · ${event.object_id}` : ""}
                </td>
                <td>{safeAuditDetail(event.detail)}</td>
              </tr>
            ))}
            {!loading && events.length === 0 && (
              <tr>
                <td className="empty-cell" colSpan={5}>
                  暂无符合条件的审计记录。
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function toIsoDateTime(value?: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? undefined : date.toISOString();
}

export function safeAuditDetail(detail: Record<string, unknown>): string {
  const safe = redactSensitiveValues(detail);
  return Object.keys(safe).length > 0 ? JSON.stringify(safe) : "—";
}

function redactSensitiveValues(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const blocked = /(password|passwd|secret|token|hash|content|storage|path|media)/i;
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !blocked.test(key))
      .map(([key, item]) => {
        if (item && typeof item === "object" && !Array.isArray(item)) {
          return [key, redactSensitiveValues(item)];
        }
        return [key, item];
      })
  );
}

function SongManagement({
  token,
  songs,
  selectedSong,
  onSelect,
  onCreate,
  onUpdate,
  onError
}: {
  token: string;
  songs: AdminSong[];
  selectedSong?: AdminSong;
  onSelect: (songId: string) => void;
  onCreate: (song: AdminSong) => void;
  onUpdate: (song: AdminSong) => void;
  onError: (error: string) => void;
}) {
  return (
    <div className="catalog-layout">
      <aside className="catalog-sidebar">
        <SongForm
          onCreate={async (values) => {
            try {
              onCreate(await createSong(token, values));
              onError("");
            } catch (reason) {
              onError(reason instanceof Error ? reason.message : "歌曲创建失败");
            }
          }}
        />
        <List
          locale={{ emptyText: "还没有歌曲，请先创建。" }}
          dataSource={songs}
          renderItem={(song) => (
            <List.Item>
              <button
                className={song.id === selectedSong?.id ? "song-nav song-nav-active" : "song-nav"}
                onClick={() => onSelect(song.id)}
                type="button"
              >
                <span>{song.title}</span>
                <small>{song.artist}</small>
                <Tag color={song.published ? "green" : "default"}>
                  {song.published ? "已发布" : "草稿"}
                </Tag>
              </button>
            </List.Item>
          )}
        />
      </aside>
      <section className="catalog-editor">
        {selectedSong ? (
          <SongEditor
            key={selectedSong.id}
            token={token}
            song={selectedSong}
            onUpdate={onUpdate}
            onError={onError}
          />
        ) : (
          <Card className="empty-editor">
            <p>从左侧创建或选择歌曲，开始维护伴奏与歌词。</p>
          </Card>
        )}
      </section>
    </div>
  );
}

function SongEditor({
  token,
  song,
  onUpdate,
  onError
}: {
  token: string;
  song: AdminSong;
  onUpdate: (song: AdminSong) => void;
  onError: (error: string) => void;
}) {
  const [coverFile, setCoverFile] = useState<File>();
  const [trackFile, setTrackFile] = useState<File>();
  const [trackId, setTrackId] = useState(song.backing_tracks.at(-1)?.id);
  const [lyricId, setLyricId] = useState<string>();
  const [lrc, setLrc] = useState("");
  const [busy, setBusy] = useState<"cover" | "track" | "lyrics" | "publish">();
  const selectedTrack = useMemo(
    () => song.backing_tracks.find((track) => track.id === trackId),
    [song.backing_tracks, trackId]
  );
  const audioSrc = useProtectedMedia(token, selectedTrack?.audio_url);
  const coverSrc = useProtectedMedia(token, song.cover_url ?? undefined);

  useEffect(() => {
    const lyrics = selectedTrack?.lyrics.at(-1);
    setLyricId(lyrics?.id);
    setLrc(lyrics?.lrc ?? serializeLrc(lyrics?.lines ?? []));
  }, [selectedTrack]);

  const perform = async (kind: NonNullable<typeof busy>, action: () => Promise<void>) => {
    try {
      setBusy(kind);
      await action();
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "操作失败");
    } finally {
      setBusy(undefined);
    }
  };

  return (
    <Card
      title={
        <span>
          {song.title} <small className="song-artist">/ {song.artist}</small>
        </span>
      }
      extra={
        <Tag color={song.published ? "green" : "gold"}>
          {song.published ? "曲库中可见" : "尚未发布"}
        </Tag>
      }
    >
      <section className="editor-section">
        <h2>1. 封面</h2>
        <div className="cover-row">
          {coverSrc ? (
            <img className="cover-preview" src={coverSrc} alt={`${song.title}封面`} />
          ) : (
            <div className="cover-placeholder">暂无封面</div>
          )}
          <div>
            <input
              aria-label="封面文件"
              accept="image/jpeg,image/png,image/webp"
              name="song-cover"
              type="file"
              onChange={(event) => setCoverFile(event.target.files?.[0])}
            />
            <Button
              disabled={!coverFile}
              loading={busy === "cover"}
              onClick={() =>
                void perform("cover", async () => {
                  if (!coverFile) return;
                  const result = await uploadSongCover(token, song.id, coverFile);
                  onUpdate({ ...song, cover_url: result.cover_url });
                })
              }
            >
              上传封面
            </Button>
          </div>
        </div>
      </section>

      <section className="editor-section">
        <h2>2. 现成伴奏</h2>
        <Space wrap>
          <input
            aria-label="伴奏文件"
            accept="audio/*"
            name="backing-track"
            type="file"
            onChange={(event) => setTrackFile(event.target.files?.[0])}
          />
          <Button
            type="primary"
            disabled={!trackFile}
            loading={busy === "track"}
            onClick={() =>
              void perform("track", async () => {
                if (!trackFile) return;
                const track = await uploadBackingTrack(token, song.id, trackFile);
                onUpdate({ ...song, backing_tracks: [...song.backing_tracks, track] });
                setTrackId(track.id);
              })
            }
          >
            上传并处理伴奏
          </Button>
          {busy === "track" && <Tag color="processing">正在校验并生成标准伴奏</Tag>}
        </Space>
        {song.backing_tracks.length > 0 && (
          <Select
            aria-label="伴奏版本"
            className="version-select"
            value={selectedTrack?.id}
            onChange={setTrackId}
            options={song.backing_tracks.map((track) => ({
              value: track.id,
              label: `伴奏 v${track.version} · ${formatDuration(track.duration_ms)}`
            }))}
          />
        )}
        {selectedTrack && <TrackFacts track={selectedTrack} />}
      </section>

      <section className="editor-section">
        <h2>3. AI 去人声</h2>
        <SeparationPanel
          token={token}
          song={song}
          onSongUpdate={onUpdate}
          onBackingAccepted={setTrackId}
          onError={onError}
        />
      </section>

      <section className="editor-section">
        <h2>4. LRC 歌词</h2>
        <Input.TextArea
          aria-label="LRC 歌词"
          autoComplete="off"
          rows={9}
          value={lrc}
          placeholder={"[00:03.50]第一句歌词\n[00:08.20]第二句歌词"}
          onChange={(event) => setLrc(event.target.value)}
        />
        <div className="section-actions">
          <Button
            disabled={!selectedTrack || !lrc.trim()}
            loading={busy === "lyrics"}
            onClick={() =>
              void perform("lyrics", async () => {
                if (!selectedTrack) return;
                const lyrics = await saveLyrics(token, selectedTrack.id, lrc);
                const tracks = song.backing_tracks.map((track) =>
                  track.id === selectedTrack.id
                    ? { ...track, lyrics: [...track.lyrics, lyrics] }
                    : track
                );
                onUpdate({ ...song, backing_tracks: tracks });
                setLyricId(lyrics.id);
              })
            }
          >
            保存为新歌词版本
          </Button>
          {selectedTrack && selectedTrack.lyrics.length > 0 && (
            <Select
              aria-label="歌词版本"
              className="version-select"
              value={lyricId}
              onChange={setLyricId}
              options={selectedTrack.lyrics.map((lyrics) => ({
                value: lyrics.id,
                label: `歌词 v${lyrics.version}`
              }))}
            />
          )}
        </div>
        <LrcPreview audioSrc={audioSrc} lrc={lrc} />
      </section>

      <section className="editor-section publish-section">
        <h2>5. 发布</h2>
        <p className="muted">发布会固定当前伴奏版本和歌词版本，后续修改会产生新版本。</p>
        <Space wrap>
          <Button
            type="primary"
            disabled={!selectedTrack || !lyricId}
            loading={busy === "publish"}
            onClick={() =>
              void perform("publish", async () => {
                if (!selectedTrack || !lyricId) return;
                await publishSong(token, song.id, selectedTrack.id, lyricId);
                onUpdate({
                  ...song,
                  published: true,
                  published_backing_track_id: selectedTrack.id,
                  published_lyric_version_id: lyricId
                });
              })
            }
          >
            发布到研究曲库
          </Button>
          <Button
            danger
            disabled={!song.published}
            onClick={() =>
              void perform("publish", async () => {
                await unpublishSong(token, song.id);
                onUpdate({
                  ...song,
                  published: false,
                  published_backing_track_id: null,
                  published_lyric_version_id: null
                });
              })
            }
          >
            下架
          </Button>
        </Space>
      </section>
    </Card>
  );
}

function SeparationPanel({
  token,
  song,
  onSongUpdate,
  onBackingAccepted,
  onError
}: {
  token: string;
  song: AdminSong;
  onSongUpdate: (song: AdminSong) => void;
  onBackingAccepted: (trackId: string) => void;
  onError: (error: string) => void;
}) {
  const [sourceFile, setSourceFile] = useState<File>();
  const [jobs, setJobs] = useState<SeparationJob[]>([]);
  const [uploading, setUploading] = useState(false);
  const [busyJobId, setBusyJobId] = useState<string>();

  useEffect(() => {
    let active = true;
    void listSeparations(token, song.id)
      .then((items) => {
        if (active) setJobs(items);
      })
      .catch((reason) => {
        if (active) onError(reason instanceof Error ? reason.message : "分离任务加载失败");
      });
    return () => {
      active = false;
    };
  }, [onError, song.id, token]);

  const pendingJobIds = jobs
    .filter((job) => job.status === "queued" || job.status === "running")
    .map((job) => job.id)
    .join(",");

  useEffect(() => {
    if (!pendingJobIds) return;
    const timer = window.setInterval(() => {
      void Promise.all(
        pendingJobIds.split(",").map((jobId) => getSeparation(token, jobId))
      )
        .then((updates) => {
          setJobs((current) =>
            current.map((job) => updates.find((updated) => updated.id === job.id) ?? job)
          );
        })
        .catch((reason) => {
          onError(reason instanceof Error ? reason.message : "分离任务状态刷新失败");
        });
    }, 2000);
    return () => window.clearInterval(timer);
  }, [onError, pendingJobIds, token]);

  const replaceJob = (updated: SeparationJob) => {
    setJobs((current) => current.map((job) => (job.id === updated.id ? updated : job)));
  };

  const runJobAction = async (jobId: string, action: () => Promise<SeparationJob>) => {
    try {
      setBusyJobId(jobId);
      replaceJob(await action());
      onError("");
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "任务操作失败");
    } finally {
      setBusyJobId(undefined);
    }
  };

  return (
    <div className="separation-panel">
      <p className="muted">
        上传原版音乐后由服务端异步分离。生成结果必须由管理员试听并接受，才会成为伴奏版本。
      </p>
      <Space wrap>
        <input
          aria-label="原版音乐文件"
          accept="audio/*"
          name="original-music"
          type="file"
          onChange={(event) => setSourceFile(event.target.files?.[0])}
        />
        <Button
          disabled={!sourceFile}
          loading={uploading}
          onClick={() => {
            if (!sourceFile) return;
            setUploading(true);
            void createSeparation(token, song.id, sourceFile)
              .then((job) => {
                setJobs((current) => [job, ...current]);
                setSourceFile(undefined);
                onError("");
              })
              .catch((reason) => {
                onError(reason instanceof Error ? reason.message : "原版音乐上传失败");
              })
              .finally(() => setUploading(false));
          }}
        >
          上传原版并开始去人声
        </Button>
        {uploading && <Tag color="processing">正在校验原版音乐</Tag>}
      </Space>

      <List
        className="separation-list"
        locale={{ emptyText: "暂无 AI 去人声任务。" }}
        dataSource={jobs}
        renderItem={(job) => (
          <List.Item>
            <SeparationJobCard
              token={token}
              job={job}
              busy={busyJobId === job.id}
              onRetry={() =>
                void runJobAction(job.id, () => retrySeparation(token, job.id))
              }
              onReject={() =>
                void runJobAction(job.id, () => rejectSeparation(token, job.id))
              }
              onAccept={() =>
                void runJobAction(job.id, async () => {
                  const accepted = await acceptSeparation(token, job.id);
                  const acceptedTrackId = accepted.approved_backing_track_id;
                  if (acceptedTrackId) {
                    const refreshed = (await listAdminSongs(token)).find(
                      (candidate) => candidate.id === song.id
                    );
                    if (refreshed) onSongUpdate(refreshed);
                    onBackingAccepted(acceptedTrackId);
                  }
                  return accepted;
                })
              }
            />
          </List.Item>
        )}
      />
    </div>
  );
}

function SeparationJobCard({
  token,
  job,
  busy,
  onRetry,
  onAccept,
  onReject
}: {
  token: string;
  job: SeparationJob;
  busy: boolean;
  onRetry: () => void;
  onAccept: () => void;
  onReject: () => void;
}) {
  const vocalsSrc = useProtectedMedia(token, job.vocals_url ?? undefined);
  const noVocalsSrc = useProtectedMedia(token, job.no_vocals_url ?? undefined);
  const status = separationStatus(job.status);
  const canReview = job.status === "succeeded";

  return (
    <Card
      className="separation-card"
      size="small"
      title={`分离任务 · 第 ${job.attempts} 次`}
      extra={<Tag color={status.color}>{status.text}</Tag>}
    >
      <p className="separation-model">处理模型：{job.model_name}</p>
      {job.status === "failed" && (
        <Alert
          showIcon
          type="error"
          message={job.failure_message ?? "音轨分离未能完成"}
          action={
            <Button size="small" loading={busy} onClick={onRetry}>
              重试
            </Button>
          }
        />
      )}
      {job.vocals_url && job.no_vocals_url && (
        <div className="separation-tracks">
          <label>
            <span>分离人声轨</span>
            {vocalsSrc ? (
              <audio aria-label="分离人声轨" controls src={vocalsSrc} />
            ) : (
              <small>正在加载受保护音频…</small>
            )}
          </label>
          <label>
            <span>无人声伴奏候选</span>
            {noVocalsSrc ? (
              <audio aria-label="无人声伴奏候选" controls src={noVocalsSrc} />
            ) : (
              <small>正在加载受保护音频…</small>
            )}
          </label>
        </div>
      )}
      {canReview && (
        <Space className="section-actions" wrap>
          <Button type="primary" loading={busy} onClick={onAccept}>
            接受为伴奏
          </Button>
          <Button danger disabled={busy} onClick={onReject}>
            拒绝结果
          </Button>
        </Space>
      )}
      {job.status === "accepted" && job.approved_backing_track_id && (
        <p className="accepted-note">已生成伴奏版本，可继续维护 LRC 并发布。</p>
      )}
    </Card>
  );
}

function separationStatus(status: SeparationJob["status"]): { color: string; text: string } {
  return {
    queued: { color: "default", text: "等待处理" },
    running: { color: "processing", text: "正在分离" },
    succeeded: { color: "cyan", text: "等待审核" },
    failed: { color: "red", text: "处理失败" },
    accepted: { color: "green", text: "已接受" },
    rejected: { color: "default", text: "已拒绝" }
  }[status];
}

function TrackFacts({ track }: { track: AdminBackingTrack }) {
  const status = {
    processing: { color: "processing", text: "处理中" },
    approved: { color: "green", text: "伴奏已通过" },
    rejected: { color: "red", text: "处理失败" }
  }[track.review_status];
  return (
    <div className="track-facts">
      <Tag color={status.color}>{status.text}</Tag>
      <span>{track.sample_rate / 1000} kHz</span>
      <span>{track.channels === 2 ? "双声道" : `${track.channels} 声道`}</span>
      <span>源文件校验 {track.source_sha256.slice(0, 10)}…</span>
    </div>
  );
}

function formatDuration(durationMs: number): string {
  const totalSeconds = Math.round(durationMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  return `${minutes}:${String(totalSeconds % 60).padStart(2, "0")}`;
}

function serializeLrc(lines: Array<{ time_ms: number; text: string }>): string {
  return lines
    .map((line) => {
      const minutes = Math.floor(line.time_ms / 60000);
      const seconds = Math.floor((line.time_ms % 60000) / 1000);
      const centiseconds = Math.floor((line.time_ms % 1000) / 10);
      return `[${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${String(
        centiseconds
      ).padStart(2, "0")}]${line.text}`;
    })
    .join("\n");
}

function LoginForm({
  onLogin,
  error
}: {
  onLogin: (username: string, password: string) => Promise<void>;
  error: string;
}) {
  return (
    <Form<{ username: string; password: string }>
      className="admin-form"
      layout="vertical"
      name="admin-login"
      initialValues={{ username: "admin" }}
      onFinish={({ username, password }) => void onLogin(username, password)}
    >
      <Form.Item label="管理员账号" name="username" rules={[{ required: true }]}>
        <Input autoComplete="username" />
      </Form.Item>
      <Form.Item label="管理员密码" name="password" rules={[{ required: true }]}>
        <Input.Password autoComplete="current-password" />
      </Form.Item>
      {error && <Alert message={error} type="error" />}
      <Button type="primary" htmlType="submit" block>
        登录
      </Button>
    </Form>
  );
}

function ParticipantForm({
  onCreate,
  error
}: {
  onCreate: (participant: Pick<Participant, "name" | "phone" | "research_code">) => Promise<void>;
  error: string;
}) {
  return (
    <Form<{ name: string; phone: string; researchCode: string }>
      className="admin-form participant-form"
      layout="vertical"
      name="participant-create"
      onFinish={({ name, phone, researchCode }) =>
        void onCreate({ name, phone, research_code: researchCode })
      }
    >
      <Form.Item label="姓名" name="name" rules={[{ required: true }]}>
        <Input autoComplete="name" />
      </Form.Item>
      <Form.Item
        label="手机号"
        name="phone"
        rules={[{ required: true, pattern: /^1\d{10}$/ }]}
      >
        <Input autoComplete="tel" inputMode="tel" />
      </Form.Item>
      <Form.Item label="研究编号" name="researchCode" rules={[{ required: true }]}>
        <Input autoComplete="off" />
      </Form.Item>
      {error && <Alert message={error} type="error" />}
      <Form.Item>
        <Button type="primary" htmlType="submit">
          创建参与者
        </Button>
      </Form.Item>
    </Form>
  );
}

function SongForm({
  onCreate
}: {
  onCreate: (song: { title: string; artist: string }) => Promise<void>;
}) {
  const [form] = Form.useForm<{ title: string; artist: string }>();
  return (
    <Form
      form={form}
      className="song-form"
      layout="vertical"
      name="song-create"
      onFinish={async (values) => {
        await onCreate(values);
        form.resetFields();
      }}
    >
      <Form.Item label="歌曲名称" name="title" rules={[{ required: true }]}>
        <Input autoComplete="off" />
      </Form.Item>
      <Form.Item label="歌手" name="artist" rules={[{ required: true }]}>
        <Input autoComplete="off" />
      </Form.Item>
      <Button type="primary" htmlType="submit" block>
        创建歌曲
      </Button>
    </Form>
  );
}

function AdminShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <ConfigProvider theme={{ algorithm: theme.darkAlgorithm, token: { colorPrimary: "#72eefe" } }}>
      <main className="admin-shell">
        <header>
          <p className="eyebrow">VOCAEASE · 一期内部测试</p>
          <h1>{title}</h1>
        </header>
        {children}
      </main>
    </ConfigProvider>
  );
}
