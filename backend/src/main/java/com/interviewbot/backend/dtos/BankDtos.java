package com.interviewbot.backend.dtos;

import static com.interviewbot.backend.enums.BankTypes.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BankDtos {
  private BankDtos() {}

  public record Draft(
      @NotBlank @Size(min = 30, max = 4000) String text,
      @NotNull @Positive Long topicId,
      @Size(max = 100) String subtopic,
      @NotNull Difficulty difficulty,
      @NotNull QuestionType questionType,
      @NotNull @Size(max = 10) List<@NotBlank @Size(max = 60) String> tags,
      @NotBlank @Size(max = 2000) String rationale) {}

  public record Generated(@NotEmpty @Size(max = 20) List<@NotNull @Valid Draft> questions) {}

  public record Review(
      @NotNull Status decision,
      @NotNull @Min(0) @Max(100) Integer qualityScore,
      @NotNull @Min(0) @Max(100) Integer relevanceScore,
      @NotNull @Positive Long topicId,
      @Size(max = 100) String subtopic,
      @NotNull Difficulty difficulty,
      @NotNull QuestionType questionType,
      @NotNull @Size(max = 10) List<@NotBlank @Size(max = 60) String> tags,
      @NotBlank @Size(max = 3000) String reason,
      @Positive Long duplicateOfId) {}

  public record ReviewedQuestion(
      @NotNull @Positive Long questionId, @NotNull @Valid Review review) {}

  public record BatchReview(
      @NotEmpty @Size(max = 10) List<@NotNull @Valid ReviewedQuestion> reviews) {}

  public record NameRequest(@NotBlank @Size(max = 100) String name) {}

  public record RequeueRequest(@NotBlank @Size(max = 1000) String reason) {}

  public record CategoryView(Long id, String name, List<TopicView> topics) {}

  public record TopicView(
      Long id,
      Long categoryId,
      String category,
      String name,
      long approved,
      long pending,
      int deficit) {}

  public record QuestionView(
      Long id,
      String text,
      Long categoryId,
      String category,
      Long topicId,
      String topic,
      String subtopic,
      Difficulty difficulty,
      QuestionType questionType,
      List<String> tags,
      Status status,
      Source source,
      Integer qualityScore,
      Integer relevanceScore,
      Long duplicateOfId,
      int reviewAttempts,
      Instant createdAt,
      Instant updatedAt) {}

  public record ValidationView(
      Long id,
      Status previousStatus,
      Status decision,
      Integer qualityScore,
      Integer relevanceScore,
      String reason,
      String reviewer,
      String model,
      String classification,
      Long duplicateOfId,
      Instant createdAt) {}

  public record Detail(QuestionView question, List<ValidationView> validations) {}

  public record QuestionPage(
      List<QuestionView> content, long totalElements, int totalPages, int page) {}

  public record ExecutionView(
      Long id,
      String agent,
      String model,
      RunStatus status,
      int attempt,
      Long batchId,
      Long questionId,
      Instant createdAt,
      Instant finishedAt,
      String error,
      String summary) {}

  public record Dashboard(
      Map<Status, Long> counts,
      long generated,
      long reviewFailures,
      List<TopicView> coverage,
      Instant lastSuccessfulExecution,
      boolean schedulingEnabled,
      String provider,
      String model) {}
}
