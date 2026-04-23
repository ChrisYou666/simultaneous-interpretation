package com.simultaneousinterpretation.api.dto;

public record TranslateResponse(
        String translation,
        String model,
        boolean usedImages,
        boolean usedMeetingText,
        double compressionRatio  // 压缩比例 = 译文长度 / 源文本长度
) {}
