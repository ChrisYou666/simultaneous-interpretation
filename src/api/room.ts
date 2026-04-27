/**
 * 房间相关 API
 */

import { get, post, del } from "./client";
import type { ApiResult } from "./types";

// ==================== 类型定义 ====================

/**
 * 房间信息
 */
export interface RoomInfo {
  roomId: string;
  roomName: string;
  hostId: string;
  hostName: string;
  status: string;
  language: string;
  participantCount: number;
  createdAt: string;
}

/**
 * 创建房间请求
 */
export interface CreateRoomRequest {
  roomName: string;
  language: string;
  maxParticipants?: number;
}

/**
 * 加入房间请求
 */
export interface JoinRoomRequest {
  userId: string;
  username?: string;
}

/**
 * 房间列表查询参数
 */
export interface RoomListParams {
  page?: number;
  size?: number;
  status?: string;
}

// ==================== API 函数 ====================

/**
 * 房间 API
 */
export const roomApi = {
  /**
   * 创建房间
   */
  createRoom: async (data: CreateRoomRequest): Promise<ApiResult<RoomInfo>> => {
    return post<RoomInfo>("/api/v1/rooms", data);
  },

  /**
   * 获取房间信息
   */
  getRoom: async (roomId: string): Promise<ApiResult<RoomInfo>> => {
    return get<RoomInfo>(`/api/v1/rooms/${roomId}`);
  },

  /**
   * 获取房间列表
   */
  listRooms: async (params?: RoomListParams): Promise<ApiResult<RoomInfo[]>> => {
    const queryParams: Record<string, string> = {};
    if (params?.page !== undefined) queryParams.page = params.page.toString();
    if (params?.size !== undefined) queryParams.size = params.size.toString();
    if (params?.status) queryParams.status = params.status;
    return get<RoomInfo[]>("/api/v1/rooms", Object.keys(queryParams).length > 0 ? queryParams : undefined);
  },

  /**
   * 加入房间
   */
  joinRoom: async (roomId: string, data: JoinRoomRequest): Promise<ApiResult<void>> => {
    return post<void>(`/api/v1/rooms/${roomId}/join`, data);
  },

  /**
   * 离开房间
   */
  leaveRoom: async (roomId: string, data: JoinRoomRequest): Promise<ApiResult<void>> => {
    return post<void>(`/api/v1/rooms/${roomId}/leave`, data);
  },

  /**
   * 关闭房间（仅主持人）
   */
  closeRoom: async (roomId: string): Promise<ApiResult<void>> => {
    return del<void>(`/api/v1/rooms/${roomId}`);
  },

  /**
   * 获取用户已加入的房间
   */
  getMyRooms: async (): Promise<ApiResult<RoomInfo[]>> => {
    return get<RoomInfo[]>("/api/v1/rooms/my");
  },
};
