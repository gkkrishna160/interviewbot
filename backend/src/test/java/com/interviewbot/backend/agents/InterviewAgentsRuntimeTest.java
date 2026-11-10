package com.interviewbot.backend.agents;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.interviewbot.backend.config.AppProperties;
import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankConfiguration;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.config.InterviewJudgeProperties;
import com.interviewbot.backend.enums.Area;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class InterviewAgentsRuntimeTest {
  @Test
  void actualAdkAgentsUseSeparateJudgeBudgetAndKeepQuestionReasoningAndNoTools() throws Exception {
    ObjectMapper json = new ObjectMapper();
    List<JsonNode> requests = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          var request = json.readTree(exchange.getRequestBody());
          requests.add(request);
          String answer =
              request.path("format").path("properties").has("score")
                  ? "{\"score\":8.5,\"feedback\":\"Correct; explain stable hashing"
                      + " too.\",\"modelAnswer\":\"Immutability enables safe sharing, stable"
                      + " hashing and string pooling.\"}"
                  : "{\"area\":\"AI\",\"text\":\"How do tools extend an AI agent beyond generating"
                        + " a text response?\",\"topic\":\"Agentic AI"
                        + " Engineering\",\"difficulty\":\"BEGINNER\",\"questionType\":\"FUNDAMENTAL\"}";
          byte[] body =
              json.writeValueAsBytes(
                  Map.of(
                      "model",
                      "test",
                      "message",
                      Map.of("role", "assistant", "content", answer),
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
      var p =
          new BankProperties(
              false,
              10,
              15,
              60,
              10,
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
      var config = new BankConfiguration();
      var placeholder = mock(com.google.adk.models.BaseLlm.class);
      var options = new BankAgentProperties.ModelOptions(null, true, 0.6, 8192, 5);
      var mode = new AppProperties(AppProperties.Mode.INTERVIEW_MODE);
      var gateway =
          new BankAgentGateway(
              placeholder,
              placeholder,
              placeholder,
              placeholder,
              new BankAgentProperties(options, options),
              p,
              json,
              Validation.buildDefaultValidatorFactory().getValidator(),
              mode);
      var agents =
          new InterviewAgents(
              config.interviewJudgeModel(
                  p, new InterviewJudgeProperties("judge-4b", false, 1024, 8192, 5)),
              config.interviewQuestionModel(p, "question", 5),
              gateway,
              mode,
              5,
              new InterviewJudgeProperties("judge-4b", false, 1024, 8192, 5));
      assertThat(
              agents
                  .judge(
                      Map.of(
                          "question",
                          "Why is Java String immutable, and what benefits does this provide?",
                          "candidateAnswer",
                          "Strings cannot change after creation."))
                  .score())
          .isEqualTo(8.5);
      assertThat(
              agents
                  .question(
                      Map.of("area", "AI", "allowedTopics", List.of("Agentic AI Engineering")))
                  .area())
          .isEqualTo(Area.AI);
      assertThat(requests).hasSize(2);
      requests.forEach(
          r -> {
            assertThat(r.path("tools").isMissingNode() || r.path("tools").isEmpty()).isTrue();
          });
      assertThat(requests.getFirst().path("model").asText()).isEqualTo("judge-4b");
      assertThat(requests.getFirst().path("think").asBoolean()).isFalse();
      assertThat(requests.getFirst().path("options").path("num_predict").asInt()).isEqualTo(1024);
      assertThat(requests.get(1).path("model").asText()).isEqualTo("question");
      assertThat(requests.get(1).path("think").asBoolean()).isTrue();
      assertThat(requests.get(1).path("options").path("num_predict").asInt()).isEqualTo(8192);
      assertThat(requests.getFirst().path("format").path("properties").has("score")).isTrue();
      assertThat(requests.get(1).path("format").path("properties").has("area")).isTrue();
    } finally {
      server.stop(0);
    }
  }
}
