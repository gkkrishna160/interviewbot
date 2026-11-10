package com.interviewbot.backend.dtos;

import com.interviewbot.backend.enums.Area;
import com.interviewbot.backend.enums.BankTypes.*;
import jakarta.validation.constraints.*;
import java.util.*;

public final class MockDtos {

  public record Selection(@NotEmpty @Size(max = 3) List<@NotNull Area> areas) {}

  public record Next(
      @NotNull Area area,
      @NotNull @Size(max = 30) List<@NotBlank @Size(max = 10000) String> previousQuestions) {}

  public record Assessment(
      @NotBlank @Size(max = 10000) String questionToken,
      @NotBlank @Size(max = 12000) String answer) {}

  public record Judgment(
      @NotNull @DecimalMin("0") @DecimalMax("10") Double score,
      @NotBlank @Size(max = 6000) String feedback,
      @NotBlank @Size(max = 12000) String modelAnswer) {}

  public record SuppliedQuestion(
      @NotNull Area area,
      @NotBlank @Size(min = 30, max = 4000) String text,
      @NotBlank @Size(max = 100) String topic,
      @Size(max = 100) String subtopic,
      @NotNull Difficulty difficulty,
      @NotNull QuestionType questionType) {}

  public record Data(
      @NotBlank String id,
      @NotNull Area area,
      @NotBlank @Size(min = 30, max = 4000) String text,
      @NotBlank @Size(max = 100) String topic,
      @Size(max = 100) String subtopic,
      @NotNull Difficulty difficulty,
      @NotNull QuestionType questionType,
      @NotBlank String source) {}

  public record Question(Data question, String token) {}

  public record Shortfall(Area area, int count) {}

  public record Questions(List<Question> questions, List<Shortfall> shortfalls, int total) {}

  public record AreaView(Area id, String name, long approved, int questions) {}

  public record Config(String mode, int questionsPerArea, List<AreaView> areas) {}
}
