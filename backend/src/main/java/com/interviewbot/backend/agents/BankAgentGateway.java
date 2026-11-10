package com.interviewbot.backend.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.google.adk.agents.*;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.dtos.BankDtos;
import com.interviewbot.backend.exceptions.LLMException;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Real ADK runtime. Each reasoning stage has isolated context and no repository or SQL access. */
@Component
@DependsOn("bankReactiveErrors")
public class BankAgentGateway {
  private final BaseLlm model;
  private final com.interviewbot.backend.config.AppProperties mode;
  private final com.interviewbot.backend.security.PromptGuard guard =
      new com.interviewbot.backend.security.PromptGuard();
  private final BaseLlm generationModel;
  private final BaseLlm validationModel;
  private final BaseLlm validationBatchModel;
  private final BankAgentProperties agents;
  private final BankProperties properties;
  private final ObjectMapper json;
  private final Validator validator;

  public BankAgentGateway(
      @Lazy @Qualifier("bankModel") BaseLlm model,
      @Lazy @Qualifier("bankGenerationModel") BaseLlm generationModel,
      @Lazy @Qualifier("bankValidationModel") BaseLlm validationModel,
      @Lazy @Qualifier("bankValidationBatchModel") BaseLlm validationBatchModel,
      BankAgentProperties agents,
      BankProperties properties,
      ObjectMapper json,
      Validator validator,
      com.interviewbot.backend.config.AppProperties mode) {
    this.mode = mode;
    this.model = model;
    this.generationModel = generationModel;
    this.validationModel = validationModel;
    this.validationBatchModel = validationBatchModel;
    this.agents = agents;
    this.properties = properties;
    this.validator = validator;
    this.json =
        json.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }

  public String orchestrate(WorkflowTools tools, Object snapshot) {
    mode.requireGeneration();
    LlmAgent orchestrator =
        LlmAgent.builder()
            .name("question_bank_orchestrator")
            .model(model)
            .description(
                "Coordinates independent question generation and review agents through bounded"
                    + " application tools")
            .instruction(prompt("orchestrator"))
            .tools(
                FunctionTool.create(tools, "generate_for_topic"),
                FunctionTool.create(tools, "review_pending"))
            .build();
    return run(orchestrator, snapshot, properties.maxLlmCalls(), properties.cycleTimeoutSeconds());
  }

  public BankDtos.Generated generate(Object task) {
    mode.requireGeneration();
    return parse(
        run(
            agent("question_generation", "generation", generationModel),
            task,
            1,
            agents.generation().timeoutSeconds() + 15),
        BankDtos.Generated.class);
  }

  public BankDtos.Review review(Object task) {
    mode.requireGeneration();
    return parse(
        run(
            agent("question_validation", "review", validationModel),
            task,
            1,
            agents.validation().timeoutSeconds() + 15),
        BankDtos.Review.class);
  }

  public BankDtos.BatchReview reviewBatch(Object task) {
    mode.requireGeneration();
    return parse(
        run(
            agent("question_validation", "review-batch", validationBatchModel),
            task,
            1,
            agents.validation().timeoutSeconds() + 15),
        BankDtos.BatchReview.class);
  }

  private LlmAgent agent(String name, String instruction, BaseLlm specialistModel) {
    Schema output = schema(instruction);
    return LlmAgent.builder()
        .name(name)
        .model(specialistModel)
        .instruction(prompt(instruction))
        .outputSchema(output)
        .generateContentConfig(
            GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(output)
                .build())
        .disallowTransferToParent(true)
        .disallowTransferToPeers(true)
        .build();
  }

  public String run(LlmAgent agent, Object task, int maxCalls, int timeoutSeconds) {
    if (java.util.Set.of("interview_question", "interview_judge").contains(agent.name()))
      mode.requireInterview();
    else if (java.util.Set.of(
            "question_bank_orchestrator", "question_generation", "question_validation")
        .contains(agent.name())) mode.requireGeneration();
    else throw new IllegalArgumentException("Agent is not permitted");
    try {
      guard.checkPayload(json.writeValueAsString(task));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid agent input");
    }
    InMemoryRunner runner = new InMemoryRunner(agent, "interviewbot_question_bank");
    try {
      String session = UUID.randomUUID().toString();
      var events =
          runner
              .runAsync(
                  "bank-worker",
                  session,
                  Content.fromParts(Part.fromText(json.writeValueAsString(task))),
                  RunConfig.builder().autoCreateSession(true).maxLlmCalls(maxCalls).build())
              .timeout(timeoutSeconds, TimeUnit.SECONDS)
              .toList()
              .blockingGet();
      String result = null;
      for (var event : events) {
        if (event.errorCode().isPresent() || event.errorMessage().isPresent())
          throw new LLMException("Agent returned a model error");
        if (event.finishReason().map(Object::toString).orElse("").contains("MAX_TOKENS"))
          throw new LLMException("Agent output was truncated");
        if (event.finalResponse() && event.author().equals(agent.name()))
          result = event.stringifyContent();
      }
      if (result == null || result.isBlank() || result.length() > 100_000)
        throw new LLMException("Agent returned no usable final response");
      return result;
    } catch (LLMException ex) {
      throw ex;
    } catch (Exception ex) {
      if (BankReactiveErrors.isTimeout(ex))
        throw new LLMException(
            "Agent " + agent.name() + " timed out waiting for the model response", ex);
      throw new LLMException(
          "ADK agent execution failed (" + ex.getClass().getSimpleName() + ")", ex);
    } finally {
      runner.close().onErrorComplete().blockingAwait(5, TimeUnit.SECONDS);
    }
  }

  public <T> T parse(String response, Class<T> type) {
    try {
      T value = json.readValue(response, type);
      if (value == null || !validator.validate(value).isEmpty())
        throw new LLMException("Agent JSON failed required field or value validation");
      return value;
    } catch (LLMException ex) {
      throw ex;
    } catch (com.fasterxml.jackson.databind.JsonMappingException ex) {
      String path =
          ex.getPath().stream()
              .map(
                  ref ->
                      ref.getFieldName() == null ? "[" + ref.getIndex() + "]" : ref.getFieldName())
              .collect(java.util.stream.Collectors.joining("."))
              .replaceAll("[^A-Za-z0-9_.\\[\\]]", "");
      if (path.length() > 100) path = path.substring(0, 100);
      throw new LLMException(
          "Agent returned invalid structured JSON (schema mismatch at "
              + (path.isBlank() ? "root" : path)
              + ")",
          ex);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      var location = ex.getLocation();
      throw new LLMException(
          "Agent returned invalid structured JSON (syntax"
              + (location == null
                  ? ""
                  : " at line " + location.getLineNr() + ", column " + location.getColumnNr())
              + ")",
          ex);
    } catch (Exception ex) {
      throw new LLMException("Agent returned invalid structured JSON", ex);
    }
  }

  public void checkTool(String name, Object arguments) {
    mode.requireGeneration();
    guard.tool(name, arguments);
  }

  private Schema schema(String name) {
    try {
      return Schema.fromJson(
          new ClassPathResource("agents/" + name + ".schema.json")
              .getContentAsString(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException("Missing or invalid agent output schema: " + name, ex);
    }
  }

  private String prompt(String name) {
    try {
      return new ClassPathResource("agents/" + name + ".txt")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Missing agent instructions: " + name, ex);
    }
  }
}
