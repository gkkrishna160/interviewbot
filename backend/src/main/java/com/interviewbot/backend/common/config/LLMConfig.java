package com.interviewbot.backend.common.config;

import com.interviewbot.backend.ai.OllamaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OllamaProperties.class)
public class LLMConfig {
}
