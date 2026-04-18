package com.simultaneousinterpretation.integration;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenAI 兼容 HTTP 聊天调用封装（供 TranslateService 使用）。
 */
@Component
public class LlmIntegration {

    private static final Logger log = LoggerFactory.getLogger(LlmIntegration.class);

    /**
     * 同步调用聊天模型，返回助手回复文本。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param apiKey       API Key
     * @return 模型回复文本
     */
    public String chat(String systemPrompt, String userMessage, String apiKey) {
        // baseUrl 和 modelName 通过 AiProperties 在 TranslateService 中确定，此处仅封装调用
        throw new UnsupportedOperationException(
                "请使用 chat(systemPrompt, userMessage, apiKey, baseUrl, modelName)");
    }

    public String chat(String systemPrompt, String userMessage, String apiKey,
                       String baseUrl, String modelName) {
        log.debug("[LLM] baseUrl={} model={}", baseUrl, modelName);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
        var response = model.generate(List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        ));
        return response.content().text();
    }
}
