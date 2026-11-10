package com.interviewbot.backend.agents;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.langchain4j.LangChain4j;
import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankConfiguration;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.enums.BankTypes;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BankReasoningModelsTest {
  @Test
  void specialistAdkSchemasReachOllamaAsStructuredOutputConstraints() throws Exception {
    ObjectMapper json = new ObjectMapper();
    List<JsonNode> requests = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          JsonNode request = json.readTree(exchange.getRequestBody());
          requests.add(request);
          boolean generation = requests.size() == 1;
          String answer =
              generation
                  ? """
                  {"questions":[{"text":"Explain how publication through a volatile field affects visibility of earlier writes to other fields.","topicId":1,"difficulty":"INTERMEDIATE","questionType":"FUNDAMENTAL","tags":["java"],"rationale":"Checks concrete visibility semantics"}]}
                  """
                  : """
                  {"decision":"APPROVED","qualityScore":90,"relevanceScore":90,"topicId":1,"difficulty":"INTERMEDIATE","questionType":"FUNDAMENTAL","tags":["java"],"reason":"Focused and technically meaningful","duplicateOfId":null}
                  """;
          if (requests.size() == 3)
            answer = "{\"reviews\":[{\"questionId\":42,\"review\":" + answer + "}]}";
          byte[] body =
              json.writeValueAsBytes(
                  java.util.Map.of(
                      "model",
                      "test",
                      "message",
                      java.util.Map.of("role", "assistant", "content", answer),
                      "done",
                      true,
                      "done_reason",
                      "stop",
                      "prompt_eval_count",
                      20,
                      "eval_count",
                      30));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var properties =
          new BankProperties(
              false,
              1,
              15,
              10,
              1,
              0,
              10,
              30,
              8,
              10,
              70,
              70,
              "ollama",
              "test",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "");
      var options = new BankAgentProperties.ModelOptions(null, true, 0.6, 8192, 10);
      var roles = new BankAgentProperties(options, options);
      var factory = new BankConfiguration();
      var gateway =
          new BankAgentGateway(
              factory.bankModel(properties, 0.3, 8192, 8192, false),
              factory.bankGenerationModel(properties, roles, 8192),
              factory.bankValidationModel(properties, roles, 8192),
              factory.bankValidationBatchModel(properties, roles, 8192),
              roles,
              properties,
              json,
              jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
              new com.interviewbot.backend.config.AppProperties(
                  com.interviewbot.backend.config.AppProperties.Mode.GENERATION_MODE));
      assertThat(gateway.generate(java.util.Map.of("topicId", 1, "count", 1)).questions())
          .hasSize(1);
      assertThat(gateway.review(java.util.Map.of("topicId", 1)).decision())
          .isEqualTo(BankTypes.Status.APPROVED);
      assertThat(
              gateway
                  .reviewBatch(java.util.Map.of("questions", java.util.List.of(42)))
                  .reviews()
                  .getFirst()
                  .questionId())
          .isEqualTo(42L);
      assertThat(requests).hasSize(3);
      assertThat(requests.get(0).path("format").path("properties").has("questions")).isTrue();
      assertThat(requests.get(1).path("format").path("properties").has("decision")).isTrue();
      assertThat(requests.get(2).path("format").path("properties").has("reviews")).isTrue();
      requests.forEach(request -> assertThat(request.path("think").asBoolean()).isTrue());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void specialistDefaultsEnableReasoningAndAllowIndependentModelOverrides() {
    new ApplicationContextRunner()
        .withUserConfiguration(BankConfiguration.class)
        .withPropertyValues(
            "question-bank.model=qwen3:8b", "question-bank.agents.validation.model=qwen3:14b")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var roles = context.getBean(BankAgentProperties.class);
              assertThat(roles.generation().thinkingEnabled()).isTrue();
              assertThat(roles.validation().thinkingEnabled()).isTrue();
              assertThat(roles.modelForAgent("generation", "qwen3:8b")).isEqualTo("qwen3:8b");
              assertThat(roles.modelForAgent("validation", "qwen3:8b")).isEqualTo("qwen3:14b");
              assertThat(roles.modelForAgent("orchestrator", "qwen3:8b")).isEqualTo("qwen3:8b");
            });
  }

  @Test
  void actualOllamaAdaptersSendReasoningForBothSpecialistsAndKeepOnlyFinalContent()
      throws Exception {
    ObjectMapper json = new ObjectMapper();
    List<JsonNode> requests = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          requests.add(json.readTree(exchange.getRequestBody()));
          byte[] body =
              """
              {"model":"test","message":{"role":"assistant","content":"{\\"ok\\":true}","thinking":"test-only reasoning fixture"},"done":true,"done_reason":"stop","prompt_eval_count":20,"eval_count":30}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var properties =
          new BankProperties(
              false,
              1,
              15,
              10,
              1,
              0,
              10,
              30,
              8,
              10,
              70,
              70,
              "ollama",
              "qwen3:8b",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "");
      var roles =
          new BankAgentProperties(
              new BankAgentProperties.ModelOptions("generation-model", true, 0.6, 4096, 5),
              new BankAgentProperties.ModelOptions("validation-model", true, 0.6, 8192, 5));
      var factory = new BankConfiguration();
      for (var model :
          List.of(
              factory.bankGenerationModel(properties, roles, 8192),
              factory.bankValidationModel(properties, roles, 8192))) {
        var response =
            ((LangChain4j) model)
                .chatModel()
                .chat(
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(
                            List.of(
                                dev.langchain4j.data.message.UserMessage.from(
                                    "Return a JSON result.")))
                        .build());
        assertThat(response.aiMessage().text()).isEqualTo("{\"ok\":true}");
        assertThat(response.aiMessage().thinking()).isNull();
      }
      assertThat(requests).hasSize(2);
      assertThat(requests.get(0).path("model").asText()).isEqualTo("generation-model");
      assertThat(requests.get(1).path("model").asText()).isEqualTo("validation-model");
      requests.forEach(request -> assertThat(request.path("think").asBoolean()).isTrue());
      assertThat(requests.get(0).path("options").path("num_predict").asInt()).isEqualTo(4096);
      assertThat(requests.get(1).path("options").path("num_predict").asInt()).isEqualTo(8192);
    } finally {
      server.stop(0);
    }
  }
}
