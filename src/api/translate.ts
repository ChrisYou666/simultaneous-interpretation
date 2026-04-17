/**
 * 翻译相关 API
 */

import { post } from "./client";
import type { ApiResult } from "./types";

// ==================== 类型定义 ====================

/**
 * 图片数据
 */
export interface ImagePayload {
  base64: string;
  mimeType: string;
}

/**
 * 翻译请求
 */
export interface TranslateRequest {
  /** 源语言（auto 表示自动检测） */
  sourceLang?: string;
  /** 目标语言 */
  targetLang: string;
  /** 待翻译文本 */
  segment: string;
  /** 是否启用知识库 */
  kbEnabled?: boolean;
  /** 术语表/关键词 */
  keywordsText?: string;
  /** 上下文 */
  contextText?: string;
  /** 会议材料文本 */
  meetingMaterialsText?: string;
  /** 图片列表 */
  images?: ImagePayload[];
}

/**
 * 翻译响应
 */
export interface TranslateResponse {
  /** 翻译后的文本 */
  translatedText: string;
  /** 使用的模型名称 */
  modelName?: string;
  /** 是否使用了图片 */
  usedImages?: boolean;
  /** 是否使用了知识库 */
  usedMeeting?: boolean;
}

// ==================== API 函数 ====================

/**
 * 翻译 API
 */
export const translateApi = {
  /**
   * 翻译文本
   */
  translate: async (data: TranslateRequest): Promise<ApiResult<TranslateResponse>> => {
    return post<TranslateResponse>("/api/v1/ai/translate", data);
  },
};
