package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.openai")
public class AiProperties {

  /** OpenAI 兼容 API Key（可与通义百炼 DASHSCOPE_API_KEY 相同）；未配置时不调用模型 */
  private String apiKey = "";

  /** 兼容端点根 URL；默认阿里云通义兼容模式，亦可改为 https://api.openai.com/v1 */
  private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 模型名：通义如 qwen-turbo / qwen-vl-plus；OpenAI 官方如 gpt-4o-mini（须与 baseUrl 匹配） */
  private String model = "qwen-turbo";

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }
}
