import { type FormEvent, useState } from "react";

import {
  createParticipant,
  listParticipants,
  loginAdmin,
  type Participant
} from "./admin-api";

export function AdminApp() {
  const [token, setToken] = useState<string>();
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [error, setError] = useState("");

  if (!token) {
    return (
      <AdminShell title="管理员登录">
        <LoginForm
          error={error}
          onLogin={async (username, password) => {
            try {
              const accessToken = await loginAdmin(username, password);
              setToken(accessToken);
              setParticipants(await listParticipants(accessToken));
            } catch (reason) {
              setError(reason instanceof Error ? reason.message : "登录失败");
            }
          }}
        />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="参与者管理">
      <ParticipantForm
        error={error}
        onCreate={async (participant) => {
          try {
            const created = await createParticipant(token, participant);
            setParticipants((current) => [...current, created]);
            setError("");
          } catch (reason) {
            setError(reason instanceof Error ? reason.message : "创建失败");
          }
        }}
      />
      <div className="participant-grid">
        {participants.map((participant) => (
          <article className="participant-card" key={participant.id}>
            <strong>{participant.name}</strong>
            <span>{participant.research_code}</span>
            <span>{participant.phone}</span>
            <small>
              {participant.must_change_password ? "初始密码待修改" : "账号已激活"}
            </small>
          </article>
        ))}
      </div>
    </AdminShell>
  );
}

function LoginForm({
  onLogin,
  error
}: {
  onLogin: (username: string, password: string) => Promise<void>;
  error: string;
}) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  return (
    <form
      className="admin-form"
      onSubmit={(event) => {
        event.preventDefault();
        void onLogin(username, password);
      }}
    >
      <label>管理员账号<input value={username} onChange={(e) => setUsername(e.target.value)} /></label>
      <label>管理员密码<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      {error && <p className="form-error">{error}</p>}
      <button type="submit">登录</button>
    </form>
  );
}

function ParticipantForm({
  onCreate,
  error
}: {
  onCreate: (participant: Pick<Participant, "name" | "phone" | "research_code">) => Promise<void>;
  error: string;
}) {
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [researchCode, setResearchCode] = useState("");
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void onCreate({ name, phone, research_code: researchCode });
  };
  return (
    <form className="admin-form participant-form" onSubmit={submit}>
      <label>姓名<input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <label>手机号<input value={phone} onChange={(e) => setPhone(e.target.value)} /></label>
      <label>研究编号<input value={researchCode} onChange={(e) => setResearchCode(e.target.value)} /></label>
      {error && <p className="form-error">{error}</p>}
      <button type="submit">创建参与者</button>
    </form>
  );
}

function AdminShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <main className="admin-shell">
      <header><p className="eyebrow">VOCAEASE · 一期内部测试</p><h1>{title}</h1></header>
      {children}
    </main>
  );
}
