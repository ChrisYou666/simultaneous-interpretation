package com.simultaneousinterpretation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImagePayload(
    /** 如 image/jpeg、image/png */
    String mimeType,
    /** 不含 data: 前缀的 Base64 */
    String base64) {}
