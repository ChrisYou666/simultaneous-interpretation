package com.simultaneousinterpretation.api.dto;

public record TranslateResponse(String translation, String model, boolean usedImages, boolean usedMeetingText) {}
