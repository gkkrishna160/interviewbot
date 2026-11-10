package com.interviewbot.backend.e2e;

import com.fasterxml.jackson.databind.*;
import com.interviewbot.backend.InterviewBotApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-classpath-only browser fixture: no production profile, external model or application
 * database.
 */
public final class E2eApplication {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String[] FRONT = {
    "How does React decide when to render a component after its state changes?",
    "Why do React list items need stable keys when their order can change?",
    "How can effect cleanup prevent stale network responses from updating React state?",
    "What is a stale closure in a React event handler, and how can it be avoided?",
    "When can useMemo reduce expensive work during a React render?",
    "How do controlled form inputs keep React state and displayed values consistent?",
    "What does an error boundary catch in a React component tree?",
    "Why should subscriptions be cleaned up when a React component unmounts?",
    "How does batching of state updates affect reading the current value in React?",
    "When is a reducer useful for managing several related state transitions?"
  };
  private static final String[] AI = {
    "How do tools extend an AI agent beyond generating a text response?",
    "What limits does an LLM context window impose on a growing input?",
    "How do embeddings support semantic retrieval over a collection of documents?",
    "Why does retrieval-augmented generation need a grounding check?",
    "How can prompt injection in a retrieved document affect an AI workflow?",
    "Why should a tool contract validate arguments before execution?",
    "How does an evaluation dataset help compare two prompt versions?",
    "What should be logged to diagnose failures in an agent workflow?",
    "Why do retries for an agent tool need an idempotency strategy?",
    "How can a system balance model latency against answer quality?"
  };

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    if (!Set.of("interview_mode", "generation_mode").contains(mode))
      throw new IllegalArgumentException("Unknown E2E mode");
    int port = Integer.parseInt(args[1]);
    Map<String, AtomicInteger> counts = new HashMap<>();
    counts.put("AI", new AtomicInteger());
    counts.put("FRONTEND", new AtomicInteger());
    HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/api/chat",
        exchange -> {
          try {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            JsonNode input = JSON.createObjectNode();
            for (var message : request.path("messages"))
              if ("user".equals(message.path("role").asText())) {
                try {
                  input = JSON.readTree(message.path("content").asText());
                } catch (Exception ignored) {
                }
              }
            Map<String, Object> message;
            if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
              boolean generated = false, reviewed = false;
              for (var m : request.path("messages"))
                if ("tool".equals(m.path("role").asText())) {
                  String content = m.path("content").asText();
                  generated |= content.contains("questionIds");
                  reviewed |= content.contains("remainingBudget");
                }
              if (reviewed)
                message =
                    Map.of(
                        "role",
                        "assistant",
                        "content",
                        "Generated and reviewed the assigned question batch.");
              else
                message =
                    Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(
                            Map.of(
                                "function",
                                Map.of(
                                    "name",
                                    generated ? "review_pending" : "generate_for_topic",
                                    "arguments",
                                    generated
                                        ? Map.of()
                                        : Map.of(
                                            "topicId", input.path("assignedTopicId").asLong())))));
            } else {
              Object answer;
              if (request.path("format").path("properties").has("score"))
                answer =
                    Map.of(
                        "score",
                        8,
                        "feedback",
                        "Good foundation. Add the key trade-off and one short example.",
                        "modelAnswer",
                        "A reference answer explains the essential concept accurately, connects it"
                            + " to practical behavior, and gives a concise example.");
              else if (request.path("format").path("properties").has("reviews")) {
                List<Object> reviews = new ArrayList<>();
                for (var q : input.path("questions"))
                  reviews.add(
                      Map.of(
                          "questionId",
                          q.path("questionId").asLong(),
                          "review",
                          Map.of(
                              "decision",
                              "APPROVED",
                              "qualityScore",
                              90,
                              "relevanceScore",
                              90,
                              "topicId",
                              q.path("topicId").asLong(),
                              "difficulty",
                              q.path("difficulty").asText(),
                              "questionType",
                              q.path("questionType").asText(),
                              "tags",
                              List.of("essential"),
                              "reason",
                              "Clear and relevant to this difficulty level.")));
                answer = Map.of("reviews", reviews);
              } else if (request.path("format").path("properties").has("questions")) {
                List<Object> questions = new ArrayList<>();
                int number = 0;
                for (var slot : input.path("requestedQuestions"))
                  questions.add(
                      Map.of(
                          "text",
                          "Explain "
                              + slot.path("concept").asText()
                              + " and its practical implications in example "
                              + (++number)
                              + ".",
                          "topicId",
                          input.path("topicId").asLong(),
                          "subtopic",
                          "Essential concepts",
                          "difficulty",
                          slot.path("difficulty").asText(),
                          "questionType",
                          slot.path("questionType").asText(),
                          "tags",
                          List.of("essential"),
                          "rationale",
                          "Essential knowledge at the requested difficulty."));
                answer = Map.of("questions", questions);
              } else {
                String area = input.path("area").asText();
                var choices = area.equals("AI") ? AI : FRONT;
                int index =
                    counts.computeIfAbsent(area, a -> new AtomicInteger()).getAndIncrement()
                        % choices.length;
                answer =
                    Map.of(
                        "area",
                        area,
                        "text",
                        choices[index],
                        "topic",
                        area.equals("AI") ? "Agentic AI Engineering" : "React",
                        "difficulty",
                        "INTERMEDIATE",
                        "questionType",
                        "FUNDAMENTAL");
              }
              message = Map.of("role", "assistant", "content", JSON.writeValueAsString(answer));
            }
            byte[] body =
                JSON.writeValueAsBytes(
                    Map.of(
                        "model",
                        "e2e-fixture",
                        "message",
                        message,
                        "done",
                        true,
                        "done_reason",
                        "stop",
                        "prompt_eval_count",
                        20,
                        "eval_count",
                        40));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception ex) {
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    provider.start();
    var app =
        new SpringApplicationBuilder(InterviewBotApplication.class)
            .run(
                "--spring.config.import=",
                "--app.mode=" + mode,
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.datasource.url=jdbc:h2:mem:e2e_"
                    + mode
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.placeholders.legacyBackupDirectory=backend/target/e2e-schema-backups",
                "--question-bank.scheduling-enabled=false",
                "--question-bank.base-url=http://127.0.0.1:" + provider.getAddress().getPort(),
                "--interview.agent.model=e2e-fixture",
                "--interview.judge.model=e2e-judge-fixture",
                "--app.cors.allowed-origins=http://127.0.0.1:5174,http://127.0.0.1:5175",
                "--logging.file.name=backend/target/e2e-" + mode + ".log");
    JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
    String[] java = {
      "Why is String immutable in Java, and what benefits does this provide?",
      "How do Java string literals differ from objects created with new String?",
      "When should a Java class override both equals and hashCode?",
      "How does Java pass-by-value work when a method receives an object reference?",
      "What can happen when a mutable key is changed after insertion into a HashMap?",
      "How do checked and unchecked exceptions differ in Java?",
      "When should StringBuilder be preferred over repeated string concatenation?",
      "What makes a Java class immutable when it contains a mutable field?",
      "How does a synchronized block affect visibility between Java threads?",
      "Why does volatile not make a compound increment operation atomic?",
      "What conditions must hold for two Java threads to deadlock?",
      "How does an executor pool bound the number of concurrently running tasks?"
    };
    for (int i = 1; i <= 14; i++) {
      boolean backend = i <= 12;
      String area = backend ? "BACKEND" : "FRONTEND";
      String table = backend ? "qb_backend_question" : "qb_frontend_question";
      String text =
          backend
              ? java[i - 1]
              : i == 13
                  ? "How do React props differ from component-local state?"
                  : "When should shared state be lifted to a common React parent?";
      jdbc.update("insert into qb_question_identity(id,bank) values (?,?)", i, area);
      jdbc.update(
          "insert into "
              + table
              + " (id,created_at,updated_at,version,text,fingerprint,topic_id,difficulty,question_type,status,source,review_attempts,bank)"
              + " values"
              + " (?,current_timestamp,current_timestamp,0,?,?,?,'BEGINNER','FUNDAMENTAL','APPROVED','MANUAL',0,?)",
          i,
          text,
          "e2e-" + i,
          backend ? 1 : 22,
          area);
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  app.close();
                  provider.stop(0);
                }));
    System.out.println("E2E_READY " + mode + " port=" + port);
  }
}
