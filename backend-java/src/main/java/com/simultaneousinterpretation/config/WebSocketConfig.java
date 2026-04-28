package com.simultaneousinterpretation.config;

import com.simultaneousinterpretation.asr.AsrWebSocketHandler;
import com.simultaneousinterpretation.asr.JwtHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final AsrWebSocketHandler asrWebSocketHandler;
  private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

  public WebSocketConfig(
      AsrWebSocketHandler asrWebSocketHandler,
      JwtHandshakeInterceptor jwtHandshakeInterceptor) {
    this.asrWebSocketHandler = asrWebSocketHandler;
    this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
  }

  /**
   * 全局 WebSocket 容器工厂：PCM 帧上限 128KB（4096采样 × 2字节 + 8字节前缀 ≈ 8.2KB，留足余量）。
   * 直接作用于 Tomcat/Undertow 的 WebSocket session 级别，解决 frame too large / buffer too small 问题。
   */
  @Bean
  public ServletServerContainerFactoryBean servletServerContainerFactoryBean() {
    ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
    factory.setMaxBinaryMessageBufferSize(128 * 1024);
    factory.setMaxTextMessageBufferSize(128 * 1024);
    return factory;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(asrWebSocketHandler, "/ws/asr")
        .addInterceptors(jwtHandshakeInterceptor)
        // Spring 6：用 Origin 模式替代已弃用的 setAllowedOrigins("*")，否则浏览器带 Origin 时握手易失败
        .setAllowedOriginPatterns("*");
  }
}
