package com.interviewbot.backend.agents;

import static com.interviewbot.backend.enums.BankTypes.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankConfiguration;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.dtos.BankDtos;
import com.interviewbot.backend.services.QuestionCoveragePlan;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in real Ollama check, without an application database or scheduler. */
@EnabledIfEnvironmentVariable(named = "QUESTION_BANK_LIVE_REASONING_TEST", matches = "true")
class LiveReasoningAgentsTest {
  @Test
  void generateAndIndependentlyReviewWithThinkingEnabled() {
    int count =
        Integer.parseInt(System.getenv().getOrDefault("QUESTION_BANK_LIVE_BATCH_SIZE", "1"));
    String generation = System.getenv().getOrDefault("QUESTION_BANK_GENERATION_MODEL", "qwen3:8b");
    String validation = System.getenv().getOrDefault("QUESTION_BANK_VALIDATION_MODEL", "qwen3:8b");
    var properties =
        new BankProperties(
            false,
            count,
            15,
            60,
            10,
            0,
            180,
            1800,
            8,
            10,
            70,
            70,
            "ollama",
            "qwen3:8b",
            System.getenv().getOrDefault("QUESTION_BANK_BASE_URL", "http://localhost:11434"),
            "");
    var roles =
        new BankAgentProperties(
            new BankAgentProperties.ModelOptions(generation, true, 0.6, 8192, 600),
            new BankAgentProperties.ModelOptions(validation, true, 0.6, 8192, 360));
    var config = new BankConfiguration();
    var gateway =
        new BankAgentGateway(
            config.bankModel(properties, 0.3, 8192, 8192, false),
            config.bankGenerationModel(properties, roles, 8192),
            config.bankValidationModel(properties, roles, 8192),
            config.bankValidationBatchModel(properties, roles, 8192),
            roles,
            properties,
            new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator(),
            new com.interviewbot.backend.config.AppProperties(
                com.interviewbot.backend.config.AppProperties.Mode.GENERATION_MODE));
    long topicId = count > 1 ? 1 : 37;
    String topic = count > 1 ? "Core Java" : "Agentic AI Engineering";
    var task =
        Map.<String, Object>of(
            "topicId",
            topicId,
            "topic",
            topic,
            "count",
            count,
            "requestedQuestions",
            QuestionCoveragePlan.create(topic, count, Map.of()),
            "existing",
            List.of(),
            "rejectionFeedback",
            List.of());
    long start = System.nanoTime();
    var generated = gateway.generate(task);
    QuestionCoveragePlan.validate(generated, task);
    assertThat(generated.questions())
        .hasSize(count)
        .allSatisfy(q -> assertThat(q.topicId()).isEqualTo(topicId));
    System.out.printf(
        "LIVE_GENERATION_OK model=%s count=%d elapsedSeconds=%.1f%n",
        generation, count, (System.nanoTime() - start) / 1e9);
    generated
        .questions()
        .forEach(
            q ->
                System.out.printf(
                    "LIVE_QUESTION difficulty=%s type=%s text=%s%n",
                    q.difficulty(), q.questionType(), q.text()));
    int approved = 0;
    for (int offset = 0; offset < count; offset += 5) {
      List<Map<String, Object>> inputs = new ArrayList<>();
      Set<Long> expected = new HashSet<>();
      for (int i = offset; i < Math.min(count, offset + 5); i++) {
        long id = i + 1;
        expected.add(id);
        var q = generated.questions().get(i);
        inputs.add(
            Map.of(
                "questionId",
                id,
                "text",
                q.text(),
                "topicId",
                topicId,
                "difficulty",
                q.difficulty(),
                "questionType",
                q.questionType(),
                "tags",
                q.tags(),
                "approvedCandidates",
                List.of()));
      }
      long reviewStart = System.nanoTime();
      var reviewed =
          gateway.reviewBatch(
              Map.of(
                  "questions",
                  inputs,
                  "taxonomy",
                  List.of(Map.of("id", topicId, "name", topic)),
                  "minimumQualityScore",
                  70,
                  "minimumRelevanceScore",
                  70));
      assertThat(reviewed.reviews()).hasSize(inputs.size());
      assertThat(reviewed.reviews().stream().map(BankDtos.ReviewedQuestion::questionId))
          .doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(expected);
      for (var r : reviewed.reviews()) {
        assertThat(r.review().decision()).isNotEqualTo(Status.PENDING_REVIEW);
        assertThat(r.review().topicId()).isEqualTo(topicId);
        if (r.review().decision() == Status.APPROVED) {
          approved++;
          assertThat(r.review().qualityScore()).isGreaterThanOrEqualTo(70);
          assertThat(r.review().relevanceScore()).isGreaterThanOrEqualTo(70);
        }
        System.out.printf(
            "LIVE_REVIEW id=%d decision=%s quality=%d relevance=%d reason=%s%n",
            r.questionId(),
            r.review().decision(),
            r.review().qualityScore(),
            r.review().relevanceScore(),
            r.review().reason());
      }
      System.out.printf(
          "LIVE_REVIEW_BATCH_OK model=%s count=%d elapsedSeconds=%.1f%n",
          validation, inputs.size(), (System.nanoTime() - reviewStart) / 1e9);
    }
    System.out.printf(
        "LIVE_REASONING_BATCH_OK generated=%d approved=%d totalSeconds=%.1f%n",
        count, approved, (System.nanoTime() - start) / 1e9);
  }
}
