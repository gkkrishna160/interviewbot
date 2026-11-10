package com.interviewbot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    com.interviewbot.backend.config.AppProperties.class)
public class InterviewBotApplication {
  public static void main(String[] args) {
    SpringApplication.run(InterviewBotApplication.class, args);
  }
}
