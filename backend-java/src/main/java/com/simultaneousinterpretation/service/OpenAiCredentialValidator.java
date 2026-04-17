package com.simultaneousinterpretation.service;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** 校验 OpenAI 兼容端点的 Base URL 与 API Key 格式（配置来自 application / 环境变量）。 */
public final class OpenAiCredentialValidator {

  private OpenAiCredentialValidator() {}

  public static void validateApiKeyIfPresent(String apiKey) {
    if (!StringUtils.hasText(apiKey)) {
      return;
    }
    String trimmed = apiKey.trim();
    if (looksLikeHttpUrl(trimmed)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "API Key 不能是网址。请填写供应商密钥，接口根地址填在 Base URL。");
    }
  }

  public static void validateBaseUrlIfPresent(String baseUrl) {
    if (!StringUtils.hasText(baseUrl)) {
      return;
    }
    validateHttpBaseUrl(baseUrl.trim());
  }

  private static boolean looksLikeHttpUrl(String s) {
    String lower = s.toLowerCase();
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  private static void validateHttpBaseUrl(String s) {
    try {
      URI u = URI.create(s);
      String scheme = u.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Base URL 须以 http:// 或 https:// 开头。");
      }
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base URL 格式无效。");
    }
  }
}
