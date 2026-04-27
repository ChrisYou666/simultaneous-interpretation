package com.simultaneousinterpretation.common;

import com.simultaneousinterpretation.domain.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 用于业务逻辑中的可预知异常，统一异常处理
 *
 * @author System
 * @version 1.0.0
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final int code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 详细错误信息（用于日志）
     */
    private final String detailMessage;

    /**
     * 构造器（使用错误码枚举）
     *
     * @param errorCode 错误码枚举
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.detailMessage = null;
    }

    /**
     * 构造器（使用错误码枚举，带自定义消息）
     *
     * @param errorCode     错误码枚举
     * @param customMessage 自定义错误消息
     */
    public BizException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.code = errorCode.getCode();
        this.message = customMessage;
        this.detailMessage = errorCode.getMessage();
    }

    /**
     * 构造器（使用错误码枚举，带格式化消息）
     *
     * @param errorCode 错误码枚举
     * @param args      格式化参数
     */
    public BizException(ErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.code = errorCode.getCode();
        this.message = String.format(errorCode.getMessage(), args);
        this.detailMessage = null;
    }

    /**
     * 构造器（使用错误码和消息）
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.detailMessage = null;
    }

    /**
     * 构造器（使用错误码、消息和原因）
     *
     * @param code    错误码
     * @param message 错误消息
     * @param cause   异常原因
     */
    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
        this.detailMessage = cause != null ? cause.getMessage() : null;
    }

    /**
     * 构造器（使用错误码枚举和原因）
     *
     * @param errorCode 错误码枚举
     * @param cause     异常原因
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.detailMessage = cause != null ? cause.getMessage() : null;
    }

    /**
     * 获取完整错误信息
     *
     * @return 完整错误信息
     */
    public String getFullMessage() {
        if (detailMessage != null) {
            return String.format("%s [%s]", message, detailMessage);
        }
        return message;
    }

    /**
     * 判断是否为指定错误码
     *
     * @param errorCode 错误码枚举
     * @return true 表示匹配
     */
    public boolean is(ErrorCode errorCode) {
        return this.code == errorCode.getCode();
    }

    /**
     * 判断是否为指定错误码
     *
     * @param code 错误码
     * @return true 表示匹配
     */
    public boolean is(int code) {
        return this.code == code;
    }

    @Override
    public String toString() {
        return String.format("BizException{code=%d, message='%s'}", code, message);
    }
}
