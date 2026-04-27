package com.simultaneousinterpretation.config;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 同传流水线调参配置（ASR 阶段参数）。
 *
 * <p>支持两种模式：
 * <ol>
 *   <li>启动时从 application.yml 读取（静态配置）</li>
 *   <li>运行时通过 {@code /api/tuning/params} REST 接口动态修改（立即生效，无需重启）</li>
 * </ol>
 */
@Component
@ConfigurationProperties(prefix = "app.tuning")
public class PipelineTuningParams {

    private static final Logger log = LoggerFactory.getLogger(PipelineTuningParams.class);

    // ─── ASR 阶段 ─────────────────────────────────────────────────────────────

    private String asrProvider = "dashscope";
    private String dashscopeWsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";
    private String dashscopeModel = "gummy-realtime-v1";
    private int dashscopeSampleRate = 16000;
    private boolean dashscopeSemanticPunctuation = false;
    private int deepgramSampleRate = 48000;
    private String deepgramModel = "nova-2";

    private final ConcurrentHashMap<String, Object> runtimeOverrides = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[Tuning] 初始化完成：asrProvider={} dashscopeModel={}", asrProvider, dashscopeModel);
    }

    // ─── 动态更新 ─────────────────────────────────────────────────────────────

    public java.util.List<String> apply(Map<String, Object> params) {
        java.util.List<String> affected = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            boolean ok = switch (k) {
                case "dashscopeSemanticPunctuation" ->
                        setIfBool("dashscopeSemanticPunctuation", v, x -> dashscopeSemanticPunctuation = x);
                default -> false;
            };
            if (ok) {
                runtimeOverrides.put(k, v);
                affected.add(k);
            }
        }
        if (!affected.isEmpty()) {
            log.info("[Tuning] 动态更新: {}", affected);
        }
        return affected;
    }

    // ─── 快照（供前端展示）────────────────────────────────────────────────────

    public Map<String, Object> snapshot() {
        return Map.of(
                "asrProvider", asrProvider,
                "dashscopeWsUrl", dashscopeWsUrl,
                "dashscopeModel", dashscopeModel,
                "dashscopeSampleRate", dashscopeSampleRate,
                "dashscopeSemanticPunctuation", dashscopeSemanticPunctuation,
                "deepgramSampleRate", deepgramSampleRate,
                "deepgramModel", deepgramModel
        );
    }

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public String getAsrProvider() { return asrProvider; }
    public void setAsrProvider(String v) { this.asrProvider = v; }
    public String getDashscopeWsUrl() { return dashscopeWsUrl; }
    public void setDashscopeWsUrl(String v) { this.dashscopeWsUrl = v; }
    public String getDashscopeModel() { return dashscopeModel; }
    public void setDashscopeModel(String v) { this.dashscopeModel = v; }
    public int getDashscopeSampleRate() { return dashscopeSampleRate; }
    public void setDashscopeSampleRate(int v) { this.dashscopeSampleRate = v; }
    public boolean isDashscopeSemanticPunctuation() { return dashscopeSemanticPunctuation; }
    public void setDashscopeSemanticPunctuation(boolean v) { this.dashscopeSemanticPunctuation = v; }
    public int getDeepgramSampleRate() { return deepgramSampleRate; }
    public void setDeepgramSampleRate(int v) { this.deepgramSampleRate = v; }
    public String getDeepgramModel() { return deepgramModel; }
    public void setDeepgramModel(String v) { this.deepgramModel = v; }

    // ─── 私有工具 ─────────────────────────────────────────────────────────────

    private boolean setIfBool(String name, Object v, java.util.function.Consumer<Boolean> setter) {
        if (v instanceof Boolean b) { setter.accept(b); return true; }
        if (v instanceof String s) { setter.accept(Boolean.parseBoolean(s)); return true; }
        return false;
    }
}
