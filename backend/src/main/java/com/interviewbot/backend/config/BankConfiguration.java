package com.interviewbot.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
  BankProperties.class,
  BankAgentProperties.class,
  InterviewJudgeProperties.class
})
public class BankConfiguration {
  @Bean
  @Lazy
  public BaseLlm bankModel(
      BankProperties p,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.temperature:0.3}")
          double temperature,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.max-output-tokens:8192}")
          int maxTokens,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.context-window:8192}")
          int contextWindow,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.thinking-enabled:false}")
          boolean thinkingEnabled) {
    return createModel(
        p,
        p.model(),
        temperature,
        maxTokens,
        contextWindow,
        thinkingEnabled,
        p.executionTimeoutSeconds(),
        null);
  }

  @Bean
  @Lazy
  public BaseLlm bankGenerationModel(
      BankProperties p,
      BankAgentProperties agents,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.context-window:8192}")
          int contextWindow) {
    return specialist(p, agents.generation(), contextWindow, "generation");
  }

  @Bean
  @Lazy
  public BaseLlm bankValidationModel(
      BankProperties p,
      BankAgentProperties agents,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.context-window:8192}")
          int contextWindow) {
    return specialist(p, agents.validation(), contextWindow, "review");
  }

  private BaseLlm specialist(
      BankProperties p,
      BankAgentProperties.ModelOptions options,
      int contextWindow,
      String schema) {
    return createModel(
        p,
        options.modelOr(p.model()),
        options.temperature(),
        options.maxOutputTokens(),
        contextWindow,
        options.thinkingEnabled(),
        options.timeoutSeconds(),
        outputFormat(schema));
  }

  @Bean
  @Lazy
  public BaseLlm interviewJudgeModel(BankProperties p, InterviewJudgeProperties judge) {
    return createModel(
        p,
        judge.model(),
        0.3,
        judge.maxOutputTokens(),
        judge.contextWindow(),
        judge.thinkingEnabled(),
        judge.timeoutSeconds(),
        outputFormat("interview-judge"));
  }

  @Bean
  @Lazy
  public BaseLlm interviewQuestionModel(
      BankProperties p,
      @org.springframework.beans.factory.annotation.Value("${interview.agent.model:qwen3:8b}")
          String model,
      @org.springframework.beans.factory.annotation.Value("${interview.agent.timeout-seconds:360}")
          int timeout) {
    return createModel(
        p, model, 0.6, 8192, 8192, true, timeout, outputFormat("interview-question"));
  }

  @Bean
  @Lazy
  public BaseLlm bankValidationBatchModel(
      BankProperties p,
      BankAgentProperties agents,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.model-options.context-window:8192}")
          int contextWindow) {
    return specialist(p, agents.validation(), contextWindow, "review-batch");
  }

  private BaseLlm createModel(
      BankProperties p,
      String modelName,
      double temperature,
      int maxTokens,
      int contextWindow,
      boolean thinkingEnabled,
      int timeoutSeconds,
      ResponseFormat responseFormat) {
    ChatModel model;
    if (p.provider().equals("ollama")) {
      model =
          OllamaChatModel.builder()
              .baseUrl(p.baseUrl())
              .modelName(modelName)
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .temperature(temperature)
              .numPredict(maxTokens)
              .numCtx(contextWindow)
              .think(thinkingEnabled)
              .returnThinking(false)
              .responseFormat(responseFormat)
              .maxRetries(0)
              .logRequests(false)
              .logResponses(false)
              .build();
    } else {
      model =
          OpenAiChatModel.builder()
              .baseUrl(p.baseUrl())
              .apiKey(p.apiKey())
              .modelName(modelName)
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .temperature(temperature)
              .maxTokens(maxTokens)
              .responseFormat(responseFormat)
              .maxRetries(0)
              .logRequests(false)
              .logResponses(false)
              .build();
    }
    return LangChain4j.builder()
        .chatModel(
            new com.interviewbot.backend.security.GuardedChatModel(
                model, responseFormat == null, modelName))
        .modelName(modelName)
        .build();
  }

  private ResponseFormat outputFormat(String name) {
    try {
      ObjectMapper json = new ObjectMapper();
      JsonNode schema =
          json.readTree(new ClassPathResource("agents/" + name + ".schema.json").getInputStream());
      normalizeSchema(schema);
      return ResponseFormat.builder()
          .type(ResponseFormatType.JSON)
          .jsonSchema(
              JsonSchema.builder()
                  .name("question_bank_" + name)
                  .rootElement(JsonRawSchema.from(json.writeValueAsString(schema)))
                  .build())
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to load structured output schema: " + name, ex);
    }
  }

  private void normalizeSchema(JsonNode node) {
    if (node instanceof ObjectNode object && object.path("type").isTextual()) {
      String type = object.path("type").asText().toLowerCase(java.util.Locale.ROOT);
      object.put("type", type);
      if (type.equals("object")) object.put("additionalProperties", false);
      if (object.path("nullable").asBoolean()) {
        object.putArray("type").add(type).add("null");
        object.remove("nullable");
      }
    }
    if (node.isContainerNode()) node.forEach(this::normalizeSchema);
  }
}
