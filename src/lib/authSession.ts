const TOKEN = "si_jwt";
const USER = "si_username";
const ROLE = "si_role";

export type AuthSession = {
  token: string;
  username: string;
  role: "ADMIN" | "USER";
};

export function saveSession(token: string, username: string, role: string): void {
  localStorage.setItem(TOKEN, token);
  localStorage.setItem(USER, username);
  localStorage.setItem(ROLE, role);
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN);
  localStorage.removeItem(USER);
  localStorage.removeItem(ROLE);
}

export function getSession(): AuthSession | null {
  const token = localStorage.getItem(TOKEN);
  const username = localStorage.getItem(USER);
  const role = localStorage.getItem(ROLE);
  if (!token || !username || !role) return null;
  if (role !== "ADMIN" && role !== "USER") return null;
  return { token, username, role };
}
