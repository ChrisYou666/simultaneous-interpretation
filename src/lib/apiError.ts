/**
 * 与后端 {@code ApiErrorBody} 对齐的统一错误 JSON。
 */
export type ApiErrorBody = {
  success?: boolean;
  error?: string;
  message?: string;
  detail?: string;
  path?: string;
  status?: number;
  timestamp?: string;
};

/**
 * 将 HTTP 错误响应体格式化为可读多行文案（主说明 + 技术详情 + 路径）。
 */
export function formatApiError(res: Response, bodyText: string): string {
  const trimmed = bodyText.trim();
  if (!trimmed) {
    return `请求失败（HTTP ${res.status}）`;
  }
  try {
    const j = JSON.parse(trimmed) as ApiErrorBody;
    const lines: string[] = [];
    if (j.message) {
      lines.push(j.message);
    }
    if (j.detail) {
      lines.push(`技术详情：${j.detail}`);
    }
    if (j.error && lines.length === 0) {
      lines.push(j.error);
    }
    if (j.path) {
      lines.push(`接口：${j.path}`);
    }
    if (lines.length > 0) {
      return lines.join("\n");
    }
  } catch {
    // 非 JSON（如反向代理 HTML）
  }
  if (trimmed.length > 800) {
    return trimmed.slice(0, 800) + "…";
  }
  return trimmed;
}

export async function readApiErrorMessage(res: Response): Promise<string> {
  const text = await res.text();
  return formatApiError(res, text);
}
