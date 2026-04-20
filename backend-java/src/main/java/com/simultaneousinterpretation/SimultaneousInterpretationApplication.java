package com.simultaneousinterpretation;

import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AiProperties.class, AsrProperties.class, DashScopeProperties.class, TtsProperties.class})
public class SimultaneousInterpretationApplication {

  public static void main(String[] args) {
    SpringApplication.run(SimultaneousInterpretationApplication.class, args);
  }
}
