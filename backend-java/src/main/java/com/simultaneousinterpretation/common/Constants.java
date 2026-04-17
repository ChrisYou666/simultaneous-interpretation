package com.simultaneousinterpretation.common;

/**
 * 业务常量类
 * <p>
 * 集中管理所有业务常量，消除魔法值
 *
 * @author System
 * @version 1.0.0
 */
public final class Constants {

    private Constants() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================== 认证相关常量 ====================

    /**
     * Token 前缀
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * JWT Claim: 用户ID
     */
    public static final String CLAIM_USER_ID = "userId";

    /**
     * JWT Claim: 用户名
     */
    public static final String CLAIM_USERNAME = "username";

    /**
     * JWT Claim: 角色
     */
    public static final String CLAIM_ROLE = "role";

    /**
     * 请求头: Authorization
     */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * Session Key: 当前用户
     */
    public static final String SESSION_USER = "currentUser";

    // ==================== 房间相关常量 ====================

    /**
     * 角色: 主持人
     */
    public static final String ROLE_HOST = "host";

    /**
     * 角色: 听众
     */
    public static final String ROLE_LISTENER = "listener";

    /**
     * 默认房间最大容量
     */
    public static final int DEFAULT_ROOM_CAPACITY = 100;

    /**
     * 房间状态: 开放
     */
    public static final String ROOM_STATUS_OPEN = "open";

    /**
     * 房间状态: 关闭
     */
    public static final String ROOM_STATUS_CLOSED = "closed";

    /**
     * 房间状态: 进行中
     */
    public static final String ROOM_STATUS_IN_PROGRESS = "in_progress";

    // ==================== 语言相关常量 ====================

    /**
     * 语言: 中文
     */
    public static final String LANG_ZH = "zh";

    /**
     * 语言: 英文
     */
    public static final String LANG_EN = "en";

    /**
     * 语言: 印尼文
     */
    public static final String LANG_ID = "id";

    /**
     * 语言: 马来文
     */
    public static final String LANG_MS = "ms";

    /**
     * 语言: 泰文
     */
    public static final String LANG_TH = "th";

    // ==================== 外部服务相关常量 ====================

    /**
     * ASR 服务: Deepgram
     */
    public static final String ASR_DEEPGRAM = "deepgram";

    /**
     * ASR 服务: DashScope
     */
    public static final String ASR_DASHSCOPE = "dashscope";

    /**
     * TTS 服务: DashScope
     */
    public static final String TTS_DASHSCOPE = "dashscope";

    /**
     * 默认超时时间（毫秒）
     */
    public static final int DEFAULT_TIMEOUT_MS = 30000;

    /**
     * 长超时时间（毫秒）
     */
    public static final int LONG_TIMEOUT_MS = 60000;

    /**
     * 短超时时间（毫秒）
     */
    public static final int SHORT_TIMEOUT_MS = 10000;

    // ==================== 分页相关常量 ====================

    /**
     * 默认页码
     */
    public static final int DEFAULT_PAGE_NUM = 1;

    /**
     * 默认每页大小
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 最大每页大小
     */
    public static final int MAX_PAGE_SIZE = 100;

    // ==================== 翻译相关常量 ====================

    /**
     * 最大翻译文本长度
     */
    public static final int MAX_TRANSLATE_TEXT_LENGTH = 5000;

    /**
     * 最大图片数量
     */
    public static final int MAX_IMAGE_COUNT = 9;

    /**
     * 单张图片最大字节数（5MB）
     */
    public static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    // ==================== WebSocket 相关常量 ====================

    /**
     * WebSocket 端点路径
     */
    public static final String WS_ENDPOINT = "/ws/asr";

    /**
     * WebSocket 房间端点路径
     */
    public static final String WS_ROOM_ENDPOINT = "/ws/room";

    /**
     * 最大 WebSocket 消息大小（字节）
     */
    public static final int MAX_WS_MESSAGE_SIZE = 10 * 1024 * 1024;

    // ==================== 缓存相关常量 ====================

    /**
     * 模型缓存有效期（秒）
     */
    public static final long MODEL_CACHE_TTL_SECONDS = 3600;

    /**
     * 用户会话缓存有效期（秒）
     */
    public static final long USER_SESSION_TTL_SECONDS = 1800;

    // ==================== AI 模型相关常量 ====================

    /**
     * 默认 AI 模型
     */
    public static final String DEFAULT_AI_MODEL = "gpt-4o-mini";

    /**
     * AI 模型温度默认值
     */
    public static final double DEFAULT_AI_TEMPERATURE = 0.7;

    /**
     * AI 最大 Token 数
     */
    public static final int DEFAULT_MAX_TOKENS = 2000;

    // ==================== TTS 相关常量 ====================

    /**
     * TTS 语速默认值
     */
    public static final double DEFAULT_TTS_RATE = 1.0;

    /**
     * TTS 音调默认值
     */
    public static final double DEFAULT_TTS_PITCH = 1.0;

    /**
     * TTS 音量默认值
     */
    public static final double DEFAULT_TTS_VOLUME = 1.0;

    // ==================== 业务错误消息 ====================

    /**
     * 错误消息: 用户名或密码错误
     */
    public static final String MSG_LOGIN_FAILED = "用户名或密码错误";

    /**
     * 错误消息: Token 已过期
     */
    public static final String MSG_TOKEN_EXPIRED = "Token已过期，请重新登录";

    /**
     * 错误消息: 房间不存在
     */
    public static final String MSG_ROOM_NOT_FOUND = "房间不存在";

    /**
     * 错误消息: 房间已满
     */
    public static final String MSG_ROOM_FULL = "房间已满，请稍后再试";

    /**
     * 错误消息: 无权限操作
     */
    public static final String MSG_NO_PERMISSION = "您没有权限执行此操作";

    /**
     * 错误消息: 服务不可用
     */
    public static final String MSG_SERVICE_UNAVAILABLE = "服务暂不可用，请稍后再试";
}
