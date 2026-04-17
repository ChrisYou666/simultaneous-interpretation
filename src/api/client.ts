/**
 * API 客户端配置
 * 基于 fetch 的统一 HTTP 客户端
 */

import type { ApiResult } from "./types";

// ==================== 类型定义 ====================

/**
 * API 异常类
 */
export class ApiException extends Error {
  constructor(
    public readonly code: number,
    public readonly message: string,
    public readonly traceId?: string
  ) {
    super(message);
    this.name = "ApiException";
  }

  toString(): string {
    return `ApiException(code=${this.code}, message=${this.message})`;
  }
}

// ==================== 配置 ====================

/**
 * 获取 API Base URL
 */
const getBaseUrl = (): string => {
  const backend = import.meta.env.VITE_API_BASE_URL?.trim();
  const forceDirect =
    import.meta.env.VITE_API_DIRECT === "true" ||
    import.meta.env.VITE_API_DIRECT === "1";

  if (import.meta.env.DEV && !forceDirect) {
    return "";
  } else if (backend) {
    return backend.replace(/\/$/, "");
  } else if (import.meta.env.DEV && forceDirect) {
    throw new Error(
      "已设置 VITE_API_DIRECT，但未配置 VITE_API_BASE_URL（直连后端需要两者）。"
    );
  }
  throw new Error(
    "生产构建须设置 VITE_API_BASE_URL；本地开发默认走 Vite 代理，无需配置。"
  );
};

/**
 * 获取认证 Token
 */
const getAuthHeaders = (): Record<string, string> => {
  const token = localStorage.getItem("si_token");
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  const key = import.meta.env.VITE_API_KEY?.trim() ?? "";
  return key ? { Authorization: `Bearer ${key}`, apikey: key } : {};
};

// ==================== 通用请求 ====================

/**
 * 通用请求函数
 */
async function request<T>(
  method: string,
  url: string,
  data?: unknown,
  params?: Record<string, string>
): Promise<ApiResult<T>> {
  const baseUrl = getBaseUrl();
  let fullUrl = `${baseUrl}${url}`;

  // 添加查询参数
  if (params) {
    const searchParams = new URLSearchParams(params);
    fullUrl += `?${searchParams.toString()}`;
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Request-Time": Date.now().toString(),
    ...getAuthHeaders(),
  };

  console.debug(`[API] ${method} ${fullUrl}`);

  try {
    const response = await fetch(fullUrl, {
      method,
      headers,
      body: data ? JSON.stringify(data) : undefined,
    });

    const result = await response.json() as ApiResult<T>;

    // 检查业务错误码
    if (result.code !== 0 && result.code !== 200) {
      console.warn(`[API] 业务错误 code=${result.code}, message=${result.message}`);
      throw new ApiException(result.code, result.message, result.traceId);
    }

    console.debug(`[API] 响应成功 code=${result.code}`);
    return result;
  } catch (error) {
    if (error instanceof ApiException) {
      throw error;
    }
    if (error instanceof TypeError) {
      console.error("[API] 网络错误", error);
      throw new ApiException(-1, "网络错误，请检查网络连接");
    }
    console.error("[API] 请求错误", error);
    throw new ApiException(-1, "请求失败");
  }
}

// ==================== 导出 ====================

/**
 * 通用 GET 请求
 */
export async function get<T>(
  url: string,
  params?: Record<string, string>
): Promise<ApiResult<T>> {
  return request<T>("GET", url, undefined, params);
}

/**
 * 通用 POST 请求
 */
export async function post<T>(
  url: string,
  data?: unknown
): Promise<ApiResult<T>> {
  return request<T>("POST", url, data);
}

/**
 * 通用 PUT 请求
 */
export async function put<T>(
  url: string,
  data?: unknown
): Promise<ApiResult<T>> {
  return request<T>("PUT", url, data);
}

/**
 * 通用 DELETE 请求
 */
export async function del<T>(
  url: string
): Promise<ApiResult<T>> {
  return request<T>("DELETE", url);
}

// 导出用于 WebSocket 等特殊需求
export { getAuthHeaders, getBaseUrl };
