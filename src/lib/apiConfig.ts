import { getSession } from "./authSession";

/**
 * 前端连接自建 Java 后端；优先使用登录 JWT，其次 VITE_API_KEY（与 app.api-key 兼容）。
 */
export function resolveApiBaseAndAuth(): {
  baseUrl: string;
  authHeaders: Record<string, string>;
} {
  const backend = import.meta.env.VITE_API_BASE_URL?.trim();
  /** 开发环境是否绕过 Vite、直连后端（默认 false，避免系统里残留的 VITE_API_BASE_URL 覆盖 .env） */
  const forceDirect =
    import.meta.env.VITE_API_DIRECT === "true" ||
    import.meta.env.VITE_API_DIRECT === "1";

  let baseUrl: string;
  if (import.meta.env.DEV && !forceDirect) {
    // 开发默认走 vite.config.ts 的 /api、/ws 代理到本机后端端口（与页面同源，WebSocket 最稳）
    baseUrl = "";
  } else if (backend) {
    baseUrl = backend.replace(/\/$/, "");
  } else if (import.meta.env.DEV && forceDirect) {
    throw new Error(
      "已设置 VITE_API_DIRECT，但未配置 VITE_API_BASE_URL（直连后端需要两者）。",
    );
  } else {
    throw new Error(
      "生产构建须设置 VITE_API_BASE_URL；本地开发默认走 Vite 代理，无需配置。",
    );
  }
  const session = getSession();
  if (session?.token) {
    return {
      baseUrl,
      authHeaders: { Authorization: `Bearer ${session.token}` },
    };
  }
  const key = import.meta.env.VITE_API_KEY?.trim() ?? "";
  return {
    baseUrl,
    authHeaders: key ? { Authorization: `Bearer ${key}`, apikey: key } : {},
  };
}
