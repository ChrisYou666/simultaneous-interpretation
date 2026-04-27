package com.simultaneousinterpretation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 统一 API 错误 JSON：供前端展示 {@code message} 与可选 {@code detail}（原因/字段/技术信息）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorBody(
    boolean success,
    String error,
    String message,
    String detail,
    String path,
    int status,
    String timestamp) {

  public static ApiErrorBody of(
      String error, String message, String detail, String path, int status) {
    return new ApiErrorBody(
        false,
        error,
        message,
        detail,
        path,
        status,
        Instant.now().toString());
  }
}
