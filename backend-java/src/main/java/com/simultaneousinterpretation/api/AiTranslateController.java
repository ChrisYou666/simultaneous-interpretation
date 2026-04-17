package com.simultaneousinterpretation.api;

import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.api.dto.TranslateResponse;
import com.simultaneousinterpretation.common.Result;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import com.simultaneousinterpretation.facade.TranslateFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * AI 翻译控制器
 * <p>
 * 处理文本翻译、图片翻译等 AI 相关请求
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiTranslateController {

    private final TranslateFacade translateFacade;

    /**
     * 翻译文本
     *
     * @param request 翻译请求
     * @return 翻译结果
     */
    @PostMapping(value = "/translate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<TranslateResponse> translate(@Valid @RequestBody TranslateRequest request) {
        log.info("收到翻译请求，源语言={}, 目标语言={}, 文本长度={}, 启用知识库={}", 
                 request.getSourceLang(), request.getTargetLang(),
                 request.getSegment() != null ? request.getSegment().length() : 0,
                 request.isKbEnabled());
        long startTime = System.currentTimeMillis();

        try {
            Result<TranslateResponse> result = translateFacade.translate(request);
            
            log.info("翻译请求完成，code={}, 译文长度={}, 耗时={}ms",
                     result.getCode(),
                     result.getData() != null ? result.getData().translation().length() : 0,
                     System.currentTimeMillis() - startTime);
            
            return result;
        } catch (Exception e) {
            log.error("翻译请求异常，耗时={}ms", System.currentTimeMillis() - startTime, e);
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }
}
