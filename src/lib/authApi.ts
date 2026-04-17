import { resolveApiBaseAndAuth } from "./apiConfig";
import { formatApiError } from "./apiError";
import { clearSession, saveSession, type AuthSession } from "./authSession";

export async function login(username: string, password: string): Promise<AuthSession> {
  const { baseUrl } = resolveApiBaseAndAuth();
  const res = await fetch(`${baseUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(formatApiError(res, text));
  }
  const json = JSON.parse(text) as { token: string; username: string; role: string };
  const role = json.role === "ADMIN" || json.role === "USER" ? json.role : "USER";
  saveSession(json.token, json.username, role);
  return { token: json.token, username: json.username, role };
}

export function logout(): void {
  clearSession();
}
