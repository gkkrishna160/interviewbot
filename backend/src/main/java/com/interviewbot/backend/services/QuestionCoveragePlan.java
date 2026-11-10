package com.interviewbot.backend.services;

import static com.interviewbot.backend.enums.BankTypes.*;

import com.interviewbot.backend.dtos.BankDtos;
import java.util.*;

/**
 * Plans explicit difficulty slots from approved + pending coverage; models still write the
 * questions.
 */
public final class QuestionCoveragePlan {
  public record Slot(
      int position, Difficulty difficulty, QuestionType questionType, String concept) {}

  public static int deficit(int target, Map<Difficulty, Long> approved) {
    long missing = 0;
    for (var d : Difficulty.values()) {
      int needed = target / 3 + (d.ordinal() < target % 3 ? 1 : 0);
      missing += Math.max(0, needed - approved.getOrDefault(d, 0L));
    }
    return (int) Math.min(Integer.MAX_VALUE, missing);
  }

  public static List<Slot> create(String topic, int count, Map<Difficulty, Long> available) {
    return create(topic, count, available, available);
  }

  public static List<Slot> create(
      String topic, int count, Map<Difficulty, Long> available, Map<Difficulty, Long> attempted) {
    Map<Difficulty, Long> totals = new EnumMap<>(Difficulty.class);
    Map<Difficulty, Long> attempts = new EnumMap<>(Difficulty.class);
    for (var d : Difficulty.values()) attempts.put(d, attempted.getOrDefault(d, 0L));
    for (var d : Difficulty.values()) totals.put(d, available.getOrDefault(d, 0L));
    List<Slot> plan = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      var level =
          Arrays.stream(Difficulty.values())
              .min(Comparator.comparingLong(totals::get))
              .orElseThrow();
      long used = attempts.get(level);
      var concepts = concepts(topic, level);
      boolean designTopic =
          topic
              .toLowerCase(Locale.ROOT)
              .matches(
                  ".*(design|architecture|microservices|distributed|cloud|agentic|applied ai).*");
      var type =
          level == Difficulty.BEGINNER
              ? QuestionType.FUNDAMENTAL
              : level == Difficulty.INTERMEDIATE
                  ? new QuestionType[] {
                        QuestionType.FUNDAMENTAL, QuestionType.PRACTICAL, QuestionType.SCENARIO
                      }
                      [(int) (used % 3)]
                  : new QuestionType[] {
                        QuestionType.DEBUGGING,
                        QuestionType.SCENARIO,
                        designTopic ? QuestionType.SYSTEM_DESIGN : QuestionType.PRACTICAL,
                        designTopic ? QuestionType.ARCHITECTURE : QuestionType.FUNDAMENTAL
                      }
                      [(int) (used % 4)];
      plan.add(new Slot(i + 1, level, type, concepts.get((int) (used % concepts.size()))));
      totals.merge(level, 1L, Long::sum);
      attempts.put(level, used + 1);
    }
    return plan;
  }

  private static List<String> concepts(String topic, Difficulty level) {
    String t = topic.toLowerCase(Locale.ROOT);
    if (t.equals("core java"))
      return switch (level) {
        case BEGINNER ->
            List.of(
                "String immutability: why and its benefits",
                "String pool, literals and new String",
                "equals versus == and hashCode",
                "Java pass-by-value and object references",
                "primitive types, wrappers and autoboxing");
        case INTERMEDIATE ->
            List.of(
                "defensive copying and designing an immutable class",
                "mutable keys in hash collections",
                "StringBuilder versus StringBuffer and concatenation",
                "overloading, overriding and runtime polymorphism",
                "checked versus unchecked exception handling");
        case ADVANCED ->
            List.of(
                "diagnose an immutability bug caused by a mutable field",
                "class loading, initialization and static state",
                "generics, erasure and heap pollution",
                "resource lifetime and exception suppression",
                "design APIs with safe object publication");
      };
    if (t.contains("thread") || t.contains("concurr"))
      return switch (level) {
        case BEGINNER ->
            List.of(
                "thread versus process and shared memory",
                "race conditions and synchronized",
                "what is a deadlock and its necessary conditions",
                "volatile visibility versus atomicity");
        case INTERMEDIATE ->
            List.of(
                "lock ordering and preventing deadlocks",
                "executor pools, queues and saturation",
                "atomic classes versus locks",
                "CompletableFuture executors and error handling");
        case ADVANCED ->
            List.of(
                "diagnose a two-lock deadlock using a thread dump",
                "happens-before and safe publication",
                "starvation, livelock and lock contention",
                "bounded concurrency, cancellation and backpressure");
      };
    if (t.contains("spring"))
      return switch (level) {
        case BEGINNER ->
            List.of(
                "dependency injection and inversion of control",
                "bean scopes and lifecycle",
                "auto-configuration and configuration properties",
                "REST request validation and error responses");
        case INTERMEDIATE ->
            List.of(
                "transaction proxies and self-invocation",
                "conditional beans and configuration diagnosis",
                "test slices and dependency boundaries");
        case ADVANCED ->
            List.of(
                "diagnose transaction and persistence failures",
                "service concurrency and reliability trade-offs",
                "production startup and configuration failures");
      };
    if (t.contains("react") || t.equals("javascript"))
      return switch (level) {
        case BEGINNER ->
            List.of(
                "components, props and state ownership",
                "state updates and rendering",
                "closures and event handlers",
                "effect dependencies and cleanup");
        case INTERMEDIATE ->
            List.of(
                "stale closures and asynchronous races",
                "controlled forms and state lifting",
                "stable keys and reconciliation");
        case ADVANCED ->
            List.of(
                "diagnose unnecessary renders and memoization",
                "concurrent UI requests and cancellation",
                "state architecture and production performance");
      };
    if (t.contains("agentic") || t.contains("applied ai"))
      return switch (level) {
        case BEGINNER ->
            List.of(
                "LLM tokens and context limits",
                "prompting versus retrieval-augmented generation",
                "tool calling and what an agent does",
                "embeddings and semantic retrieval");
        case INTERMEDIATE ->
            List.of(
                "retrieval quality and grounding",
                "structured outputs and tool contracts",
                "agent state, memory and workflow orchestration",
                "building an evaluation dataset");
        case ADVANCED ->
            List.of(
                "prompt injection and permission boundaries",
                "tool retries, idempotency and recovery",
                "evaluate hallucinations, latency and cost",
                "production tracing and failure containment");
      };
    return List.of(
        switch (level) {
          case BEGINNER ->
              "a central concept in "
                  + topic
                  + ": what it means, why it exists, and a simple example";
          case INTERMEDIATE -> "implementing and comparing approaches within " + topic;
          case ADVANCED ->
              "diagnosing failures and reasoning about design trade-offs within " + topic;
        });
  }

  public static void validate(BankDtos.Generated output, Map<String, Object> task) {
    if (!(task.get("requestedQuestions") instanceof List<?> requested) || requested.size() < 3)
      return;
    Map<Difficulty, Long> expected = new EnumMap<>(Difficulty.class),
        actual = new EnumMap<>(Difficulty.class);
    for (Object item : requested) {
      Slot s = (Slot) item;
      expected.merge(s.difficulty(), 1L, Long::sum);
    }
    for (var q : output.questions()) actual.merge(q.difficulty(), 1L, Long::sum);
    if (!actual.equals(expected))
      throw new IllegalArgumentException(
          "Generated difficulty mix does not match the requested coverage plan");
  }
}
