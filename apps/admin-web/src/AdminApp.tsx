import { useState } from "react";
import { Alert, Button, Card, ConfigProvider, Form, Input, List, Tag, theme } from "antd";

import {
  createParticipant,
  listParticipants,
  loginAdmin,
  resetParticipantPassword,
  setParticipantActive,
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
      <List
        className="participant-grid"
        grid={{ gutter: 16, xs: 1, sm: 2, lg: 3 }}
        dataSource={participants}
        renderItem={(participant) => (
          <List.Item>
            <Card title={participant.name}>
              <p>{participant.research_code}</p>
              <p>{participant.phone}</p>
              <Tag color={participant.must_change_password ? "gold" : "green"}>
                {participant.must_change_password ? "初始密码待修改" : "账号已激活"}
              </Tag>
              <div className="participant-actions">
                <Button
                  onClick={async () => {
                    const updated = await setParticipantActive(token, participant.id, !participant.active);
                    setParticipants((items) =>
                      items.map((item) => (item.id === updated.id ? updated : item))
                    );
                  }}
                >
                  {participant.active ? "停用" : "恢复启用"}
                </Button>
                <Button
                  onClick={async () => {
                    await resetParticipantPassword(token, participant.id);
                    setParticipants((items) =>
                      items.map((item) =>
                        item.id === participant.id
                          ? { ...item, must_change_password: true }
                          : item
                      )
                    );
                  }}
                >
                  重置为初始密码
                </Button>
              </div>
            </Card>
          </List.Item>
        )}
      />
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
  return (
    <Form<{ username: string; password: string }>
      className="admin-form"
      layout="vertical"
      initialValues={{ username: "admin" }}
      onFinish={({ username, password }) => void onLogin(username, password)}
    >
      <Form.Item label="管理员账号" name="username" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="管理员密码" name="password" rules={[{ required: true }]}><Input.Password /></Form.Item>
      {error && <Alert message={error} type="error" />}
      <Button type="primary" htmlType="submit" block>登录</Button>
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
      onFinish={({ name, phone, researchCode }) =>
        void onCreate({ name, phone, research_code: researchCode })
      }
    >
      <Form.Item label="姓名" name="name" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item label="手机号" name="phone" rules={[{ required: true, pattern: /^1\d{10}$/ }]}><Input /></Form.Item>
      <Form.Item label="研究编号" name="researchCode" rules={[{ required: true }]}><Input /></Form.Item>
      {error && <Alert message={error} type="error" />}
      <Form.Item><Button type="primary" htmlType="submit">创建参与者</Button></Form.Item>
    </Form>
  );
}

function AdminShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <ConfigProvider theme={{ algorithm: theme.darkAlgorithm, token: { colorPrimary: "#72eefe" } }}>
      <main className="admin-shell">
        <header><p className="eyebrow">VOCAEASE · 一期内部测试</p><h1>{title}</h1></header>
        {children}
      </main>
    </ConfigProvider>
  );
}
