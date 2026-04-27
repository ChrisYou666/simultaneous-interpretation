package com.simultaneousinterpretation.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.api.dto.TranslateResponse;
import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Result;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import com.simultaneousinterpretation.service.TranslateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 翻译门面
 * <p>
 * 编排翻译相关业务逻辑，提供统一的响应结构
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslateFacade {

    private final TranslateService translateService;
    private final ObjectMapper objectMapper;

    /**
     * 翻译文本
     *
     * @param request 翻译请求
     * @return 翻译结果
     */
    public Result<TranslateResponse> translate(TranslateRequest request) {
        log.info("翻译请求开始，源语言={}, 目标语言={}, 文本长度={}", 
                 request.getSourceLang(), request.getTargetLang(),
                 request.getSegment() != null ? request.getSegment().length() : 0);
        long startTime = System.currentTimeMillis();

        try {
            // 参数日志
            if (log.isDebugEnabled()) {
                try {
                    String requestJson = objectMapper.writeValueAsString(request);
                    log.debug("翻译请求详情: {}", truncate(requestJson, 500));
                } catch (Exception e) {
                    log.debug("翻译请求序列化失败", e);
                }
            }

            TranslateResponse response = translateService.translate(request);

            log.info("翻译请求成功，译文长度={}, 耗时={}ms",
                     response.translation().length(), System.currentTimeMillis() - startTime);

            return Result.success(response);
        } catch (BizException e) {
            log.warn("翻译业务异常，源语言={}, 目标语言={}, errorCode={}, message={}", 
                     request.getSourceLang(), request.getTargetLang(), e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("翻译系统异常，源语言={}, 目标语言={}, error={}", 
                     request.getSourceLang(), request.getTargetLang(), e.getMessage(), e);
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
