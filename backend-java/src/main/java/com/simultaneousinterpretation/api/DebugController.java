package com.simultaneousinterpretation.api;

import com.simultaneousinterpretation.config.AsrProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开发诊断端点。 */
@RestController
@RequestMapping("/api/health")
public class DebugController {

  private final AsrProperties asrProperties;

  public DebugController(AsrProperties asrProperties) {
    this.asrProperties = asrProperties;
  }

  @GetMapping("/ws-check")
  public Map<String, Object> wsCheck() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "ok");
    result.put("message", "此端点可达，说明 HTTP 路由到后端正常；WebSocket 握手在 /ws/asr 路径。");

    String provider = asrProperties.getProvider();
    result.put("asr.provider", provider);

    if ("deepgram".equalsIgnoreCase(provider)) {
      boolean hasKey = StringUtils.hasText(asrProperties.getDeepgram().getApiKey());
      result.put("asr.deepgram.api-key.configured", hasKey);
      if (!hasKey) {
        result.put("warning", "DEEPGRAM_API_KEY 未配置，WebSocket 会握手成功但随后收到 ASR_NOT_CONFIGURED 错误。");
      }
    } else {
      boolean hasKey = StringUtils.hasText(asrProperties.getDashscope().getApiKey());
      result.put("asr.dashscope.api-key.configured", hasKey);
      result.put("asr.dashscope.ws-url", asrProperties.getDashscope().getWsUrl());
      result.put("asr.dashscope.model", asrProperties.getDashscope().getModel());
      result.put("asr.dashscope.sample-rate", asrProperties.getDashscope().getSampleRate());
      if (!hasKey) {
        result.put("warning", "DASHSCOPE_API_KEY 未配置，WebSocket 会握手成功但随后收到 ASR_NOT_CONFIGURED 错误。");
      }
    }
    return result;
  }
}
