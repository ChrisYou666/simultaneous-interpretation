/**
 * 统一响应结构类型定义
 * 对应后端 Result<T>
 */

/**
 * 统一 API 响应结构
 */
export interface ApiResult<T = unknown> {
  /** 响应码：0 表示成功，非 0 表示失败 */
  code: number;
  /** 响应消息 */
  message: string;
  /** 响应数据 */
  data: T;
  /** 时间戳 */
  timestamp: number;
  /** 追踪 ID */
  traceId?: string;
}

/**
 * API 错误码枚举
 * 与后端 ErrorCode 枚举保持一致
 */
export enum ApiErrorCode {
  // 认证相关 (1001-1999)
  AUTH_FAILED = 1001,
  USER_NOT_FOUND = 1002,
  PASSWORD_ERROR = 1003,
  TOKEN_EXPIRED = 1004,
  TOKEN_INVALID = 1005,
  ACCESS_DENIED = 1006,

  // 房间相关 (2001-2999)
  ROOM_NOT_FOUND = 2001,
  ROOM_FULL = 2002,
  ROOM_CLOSED = 2003,
  NOT_ROOM_OWNER = 2004,

  // 翻译相关 (3001-3999)
  TRANSLATE_FAILED = 3001,
  UNSUPPORTED_TARGET_LANGUAGE = 3002,
  TRANSLATE_TEXT_TOO_LONG = 3004,
  TRANSLATE_TEXT_EMPTY = 3005,

  // 音频相关 (4001-4999)
  AUDIO_GENERATE_FAILED = 4001,
  TTS_FAILED = 4006,

  // 系统相关 (9001-9999)
  INTERNAL_ERROR = 9001,
  SERVICE_UNAVAILABLE = 9002,
  PARAM_INVALID = 9003,
  NETWORK_ERROR = 9006,
}

/**
 * 错误码消息映射
 */
export const ErrorCodeMessage: Record<number, string> = {
  [ApiErrorCode.AUTH_FAILED]: "认证失败",
  [ApiErrorCode.USER_NOT_FOUND]: "用户不存在",
  [ApiErrorCode.PASSWORD_ERROR]: "用户名或密码错误",
  [ApiErrorCode.TOKEN_EXPIRED]: "Token已过期，请重新登录",
  [ApiErrorCode.TOKEN_INVALID]: "Token无效",
  [ApiErrorCode.ACCESS_DENIED]: "无访问权限",
  [ApiErrorCode.ROOM_NOT_FOUND]: "房间不存在",
  [ApiErrorCode.ROOM_FULL]: "房间已满，请稍后再试",
  [ApiErrorCode.ROOM_CLOSED]: "房间已关闭",
  [ApiErrorCode.NOT_ROOM_OWNER]: "非房间所有者，无权操作",
  [ApiErrorCode.TRANSLATE_FAILED]: "翻译服务调用失败",
  [ApiErrorCode.UNSUPPORTED_TARGET_LANGUAGE]: "不支持的目标语言",
  [ApiErrorCode.TRANSLATE_TEXT_TOO_LONG]: "翻译文本过长",
  [ApiErrorCode.TRANSLATE_TEXT_EMPTY]: "翻译文本不能为空",
  [ApiErrorCode.AUDIO_GENERATE_FAILED]: "音频生成失败",
  [ApiErrorCode.TTS_FAILED]: "TTS服务调用失败",
  [ApiErrorCode.INTERNAL_ERROR]: "系统内部错误，请稍后再试",
  [ApiErrorCode.SERVICE_UNAVAILABLE]: "服务暂不可用，请稍后再试",
  [ApiErrorCode.PARAM_INVALID]: "参数错误",
  [ApiErrorCode.NETWORK_ERROR]: "网络错误，请检查网络连接",
};

/**
 * 根据错误码获取错误消息
 */
export function getErrorMessage(code: number, defaultMessage?: string): string {
  return ErrorCodeMessage[code] || defaultMessage || "未知错误";
}

/**
 * 判断是否为成功响应
 */
export function isSuccess<T>(result: ApiResult<T>): boolean {
  return result.code === 0;
}
