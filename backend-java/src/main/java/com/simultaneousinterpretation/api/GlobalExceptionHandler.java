package com.simultaneousinterpretation.api;

import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Result;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 统一处理所有业务异常和系统异常，返回统一的错误响应结构
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     *
     * @param ex      业务异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex, HttpServletRequest request) {
        log.warn("业务异常，errorCode={}, message={}, path={}", 
                 ex.getCode(), ex.getMessage(), request.getRequestURI());
        
        return Result.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理 HTTP 状态异常
     *
     * @param ex      状态异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        HttpStatus hs = HttpStatus.resolve(status);
        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : (hs != null ? hs.getReasonPhrase() : "请求处理失败");
        
        log.warn("HTTP状态异常，status={}, message={}, path={}", 
                 status, message, request.getRequestURI());
        
        // 将 HTTP 状态码转换为业务错误码
        int errorCode = mapHttpStatusToErrorCode(status);
        return Result.error(errorCode, message);
    }

    /**
     * 处理参数校验异常
     *
     * @param ex      校验异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        
        log.warn("参数校验失败，detail={}, path={}", detail, request.getRequestURI());
        
        return Result.error(ErrorCode.PARAM_EMPTY.getCode(),
                "参数校验失败: " + (detail.isEmpty() ? "请检查输入" : detail));
    }

    /**
     * 处理请求体解析异常
     *
     * @param ex      解析异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnreadableException(HttpMessageNotReadableException ex, HttpServletRequest request) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null ? root.getMessage() : ex.getMessage();
        
        log.warn("请求体解析失败，detail={}, path={}", detail, request.getRequestURI());
        
        return Result.error(ErrorCode.PARAM_EMPTY.getCode(), "请求体格式错误，请检查 JSON 格式");
    }

    /**
     * 处理约束违规异常
     *
     * @param ex      约束异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleConstraintException(ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("；"));
        
        log.warn("约束违规，detail={}, path={}", detail, request.getRequestURI());
        
        return Result.error(ErrorCode.PARAM_EMPTY.getCode(),
                "参数约束不满足: " + detail);
    }

    /**
     * 处理 IllegalArgumentException
     *
     * @param ex      参数异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("非法参数，message={}, path={}", ex.getMessage(), request.getRequestURI());
        
        return Result.error(ErrorCode.PARAM_EMPTY.getCode(), ex.getMessage());
    }

    /**
     * 处理所有其他异常
     *
     * @param ex      异常
     * @param request HTTP 请求
     * @return 错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("系统异常，path={}, method={}, error={}", 
                 request.getRequestURI(), request.getMethod(), ex.getMessage(), ex);
        
        return Result.error(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 格式化字段错误
     */
    private String formatFieldError(FieldError fe) {
        String field = fe.getField();
        String msg = fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "无效";
        return field + "：" + msg;
    }

    /**
     * 将 HTTP 状态码映射为业务错误码
     */
    private int mapHttpStatusToErrorCode(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> ErrorCode.PARAM_EMPTY.getCode();
            case 401 -> ErrorCode.AUTH_FAILED.getCode();
            case 403 -> ErrorCode.ACCESS_DENIED.getCode();
            case 404 -> ErrorCode.ROOM_NOT_FOUND.getCode();
            case 500 -> ErrorCode.INTERNAL_ERROR.getCode();
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE.getCode();
            default -> ErrorCode.UNKNOWN_ERROR.getCode();
        };
    }
}
