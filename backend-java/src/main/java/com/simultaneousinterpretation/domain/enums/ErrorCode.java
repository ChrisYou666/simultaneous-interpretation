package com.simultaneousinterpretation.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 * <p>
 * 统一管理所有业务错误码，便于追踪和定位问题
 * <p>
 * 错误码规则：
 * - 1xxx: 认证相关
 * - 2xxx: 房间相关
 * - 3xxx: 翻译相关
 * - 4xxx: 音频相关
 * - 5xxx: ASR相关
 * - 9xxx: 系统相关
 *
 * @author System
 * @version 1.0.0
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== 认证相关错误码 (1001-1999) ====================
    AUTH_FAILED(1001, "认证失败"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "密码错误"),
    TOKEN_EXPIRED(1004, "Token已过期，请重新登录"),
    TOKEN_INVALID(1005, "Token无效"),
    ACCESS_DENIED(1006, "无访问权限"),
    USER_ALREADY_EXISTS(1007, "用户已存在"),
    SESSION_EXPIRED(1008, "登录会话已失效"),

    // ==================== 房间相关错误码 (2001-2999) ====================
    ROOM_NOT_FOUND(2001, "房间不存在"),
    ROOM_FULL(2002, "房间已满，请稍后再试"),
    ROOM_CLOSED(2003, "房间已关闭"),
    NOT_ROOM_OWNER(2004, "非房间所有者，无权操作"),
    ALREADY_IN_ROOM(2005, "您已在该房间中"),
    NOT_IN_ROOM(2006, "您不在该房间中"),
    ROOM_CREATE_FAILED(2007, "房间创建失败"),
    ROOM_JOIN_FAILED(2008, "加入房间失败"),
    ROOM_ID_INVALID(2009, "房间ID无效"),
    ROOM_NAME_EMPTY(2010, "房间名称不能为空"),
    ROOM_CAPACITY_EXCEEDED(2011, "房间人数已达上限"),

    // ==================== 翻译相关错误码 (3001-3999) ====================
    TRANSLATE_FAILED(3001, "翻译服务调用失败"),
    UNSUPPORTED_TARGET_LANGUAGE(3002, "不支持的目标语言"),
    UNSUPPORTED_SOURCE_LANGUAGE(3003, "不支持的源语言"),
    TRANSLATE_TEXT_TOO_LONG(3004, "翻译文本过长，请缩短后重试"),
    TRANSLATE_TEXT_EMPTY(3005, "翻译文本不能为空"),
    TRANSLATE_SERVICE_UNAVAILABLE(3006, "翻译服务暂不可用"),
    IMAGE_COUNT_EXCEEDED(3007, "图片数量超过限制"),
    IMAGE_TOO_LARGE(3008, "图片过大，请压缩后重试"),

    // ==================== 音频相关错误码 (4001-4999) ====================
    AUDIO_GENERATE_FAILED(4001, "音频生成失败"),
    AUDIO_SERVICE_UNAVAILABLE(4002, "音频服务暂不可用"),
    AUDIO_FORMAT_UNSUPPORTED(4003, "不支持的音频格式"),
    AUDIO_FILE_TOO_LARGE(4004, "音频文件过大"),
    AUDIO_SYNTHESIS_TIMEOUT(4005, "音频合成超时"),
    TTS_FAILED(4006, "TTS服务调用失败"),
    AUDIO_NO_DATA(4007, "无音频数据"),

    // ==================== ASR相关错误码 (5001-5999) ====================
    ASR_CONNECTION_FAILED(5001, "ASR连接失败"),
    ASR_SERVICE_UNAVAILABLE(5002, "ASR服务暂不可用"),
    ASR_AUTH_FAILED(5003, "ASR认证失败"),
    ASR_CONFIG_ERROR(5004, "ASR配置错误"),
    ASR_TIMEOUT(5005, "ASR服务响应超时"),
    ASR_LANGUAGE_UNSUPPORTED(5006, "ASR不支持该语言"),

    // ==================== 语言检测相关错误码 (6001-6999) ====================
    LANGUAGE_DETECT_FAILED(6001, "语言检测失败"),
    LANGUAGE_UNSUPPORTED(6002, "不支持的语言"),

    // ==================== 参数校验相关错误码 (7001-7999) ====================
    PARAM_EMPTY(7001, "参数不能为空"),
    PARAM_FORMAT_ERROR(7002, "参数格式错误"),
    PARAM_OUT_OF_RANGE(7003, "参数值超出范围"),
    PARAM_MISSING(7004, "缺少必需参数"),
    PARAM_TYPE_ERROR(7005, "参数类型错误"),

    // ==================== 系统相关错误码 (9001-9999) ====================
    INTERNAL_ERROR(9001, "系统内部错误，请稍后再试"),
    SERVICE_UNAVAILABLE(9002, "服务暂不可用，请稍后再试"),
    OPERATION_TOO_FREQUENT(9003, "操作过于频繁，请稍后再试"),
    CACHE_ERROR(9005, "缓存服务错误"),
    NETWORK_ERROR(9006, "网络错误，请检查网络连接"),
    UNKNOWN_ERROR(9099, "未知错误");

    /**
     * 错误码
     */
    private final int code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 根据错误码获取枚举
     *
     * @param code 错误码
     * @return 错误码枚举，未找到返回 UNKNOWN_ERROR
     */
    public static ErrorCode getByCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        return UNKNOWN_ERROR;
    }
}
