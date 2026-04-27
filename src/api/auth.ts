/**
 * 认证相关 API
 */

import { post } from "./client";
import type { ApiResult } from "./types";

// ==================== 类型定义 ====================

/**
 * 登录请求
 */
export interface LoginRequest {
  username: string;
  password: string;
}

/**
 * 登录响应
 */
export interface LoginResponse {
  token: string;
  username: string;
  role: string;
  userId: number;
}

/**
 * 用户会话信息
 */
export interface UserSession {
  userId: number;
  username: string;
  role: string;
}

/**
 * 本地会话信息（包含 token）
 */
export interface LocalSession {
  token: string;
  username: string;
  role: string;
  userId?: number;
}

// ==================== API 函数 ====================

/**
 * 用户登录
 */
export const authApi = {
  /**
   * 登录
   */
  login: async (data: LoginRequest): Promise<ApiResult<LoginResponse>> => {
    return post<LoginResponse>("/api/v1/auth/login", data);
  },

  /**
   * 验证 Token
   */
  validateToken: async (token: string): Promise<ApiResult<UserSession>> => {
    return post<UserSession>("/api/v1/auth/validate", { token });
  },

  /**
   * 获取当前用户信息
   */
  getCurrentUser: async (): Promise<ApiResult<UserSession>> => {
    return post<UserSession>("/api/v1/auth/current", {});
  },
};

/**
 * 认证状态管理
 */
export const authSession = {
  /**
   * 保存会话
   */
  save: (token: string, username: string, role: string, userId?: number): void => {
    localStorage.setItem("si_token", token);
    localStorage.setItem("si_username", username);
    localStorage.setItem("si_role", role);
    if (userId !== undefined) {
      localStorage.setItem("si_userId", userId.toString());
    }
  },

  /**
   * 获取本地会话（包含 token）
   */
  get: (): LocalSession | null => {
    const token = localStorage.getItem("si_token");
    const username = localStorage.getItem("si_username");
    const role = localStorage.getItem("si_role");
    const userIdStr = localStorage.getItem("si_userId");

    if (!token || !username || !role) {
      return null;
    }

    return {
      token,
      username,
      role,
      userId: userIdStr ? parseInt(userIdStr, 10) : undefined,
    };
  },

  /**
   * 获取 Token
   */
  getToken: (): string | null => {
    return localStorage.getItem("si_token");
  },

  /**
   * 清除会话
   */
  clear: (): void => {
    localStorage.removeItem("si_token");
    localStorage.removeItem("si_username");
    localStorage.removeItem("si_role");
    localStorage.removeItem("si_userId");
  },

  /**
   * 检查是否已登录
   */
  isLoggedIn: (): boolean => {
    return !!localStorage.getItem("si_token");
  },
};
