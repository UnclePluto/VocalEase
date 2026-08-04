export interface Participant {
  id: string;
  account_id: string;
  name: string;
  phone: string;
  research_code: string;
  active: boolean;
  must_change_password: boolean;
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { detail?: string };
    throw new Error(payload.detail ?? "请求失败");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export async function loginAdmin(username: string, password: string): Promise<string> {
  const result = await request<{ access_token: string }>("/api/v1/auth/admin/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
  return result.access_token;
}

export function listParticipants(token: string): Promise<Participant[]> {
  return request("/api/v1/admin/participants", {}, token);
}

export function createParticipant(
  token: string,
  participant: Pick<Participant, "name" | "phone" | "research_code">
): Promise<Participant> {
  return request(
    "/api/v1/admin/participants",
    { method: "POST", body: JSON.stringify(participant) },
    token
  );
}
