package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语言检测配置（基于字符 bigram 频率统计，纯 Java 实现）。
 *
 * <p>默认关闭（enabled=false），启用时 ASR 未返回 language 字段则由 bigram 检测。
 */
@ConfigurationProperties(prefix = "app.lang-detect")
public class LangDetectProperties {

  /** 是否启用语言检测。关闭时 fallback 为字形启发式推断。 */
  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
