package com.simultaneousinterpretation.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结构
 *
 * @author System
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 响应码：0 表示成功，非 0 表示失败
     */
    private int code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 时间戳
     */
    private long timestamp;

    /**
     * 追踪ID（用于链路追踪）
     */
    private String traceId;

    /**
     * 成功响应（带数据）
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应结果
     */
    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(0)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .traceId(generateTraceId())
                .build();
    }

    /**
     * 成功响应（无数据）
     *
     * @param <T> 数据类型
     * @return 成功响应结果
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 成功响应（带自定义消息）
     *
     * @param data    响应数据
     * @param message 自定义消息
     * @param <T>     数据类型
     * @return 成功响应结果
     */
    public static <T> Result<T> success(T data, String message) {
        return Result.<T>builder()
                .code(0)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .traceId(generateTraceId())
                .build();
    }

    /**
     * 错误响应
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应结果
     */
    public static <T> Result<T> error(int code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .traceId(generateTraceId())
                .build();
    }

    /**
     * 错误响应（使用错误码枚举）
     *
     * @param errorCode 错误码枚举
     * @param <T>       数据类型
     * @return 错误响应结果
     */
    public static <T> Result<T> error(com.simultaneousinterpretation.domain.enums.ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 错误响应（使用错误码枚举，带自定义消息）
     *
     * @param errorCode 错误码枚举
     * @param customMessage 自定义消息
     * @param <T>        数据类型
     * @return 错误响应结果
     */
    public static <T> Result<T> error(com.simultaneousinterpretation.domain.enums.ErrorCode errorCode, String customMessage) {
        return error(errorCode.getCode(), customMessage);
    }

    /**
     * 判断是否成功
     *
     * @return true 表示成功，false 表示失败
     */
    public boolean isSuccess() {
        return this.code == 0;
    }

    /**
     * 生成追踪ID
     *
     * @return 追踪ID
     */
    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
