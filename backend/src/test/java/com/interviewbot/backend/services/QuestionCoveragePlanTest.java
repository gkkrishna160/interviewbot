package com.interviewbot.backend.services;

import static com.interviewbot.backend.enums.BankTypes.*;
import static org.assertj.core.api.Assertions.*;

import com.interviewbot.backend.dtos.BankDtos;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuestionCoveragePlanTest {
  @Test
  void rejectedOrDuplicateQuestionsAdvanceConceptRotationWithoutFillingCoverage() {
    var plan =
        QuestionCoveragePlan.create(
            "Core Java",
            1,
            Map.of(Difficulty.BEGINNER, 5L, Difficulty.INTERMEDIATE, 5L),
            Map.of(Difficulty.BEGINNER, 5L, Difficulty.INTERMEDIATE, 5L, Difficulty.ADVANCED, 1L));
    assertThat(plan.getFirst().difficulty()).isEqualTo(Difficulty.ADVANCED);
    assertThat(plan.getFirst().concept()).contains("class loading");
  }

  @Test
  void advancedQuestionsCannotHideMissingFoundationsInCoverage() {
    assertThat(QuestionCoveragePlan.deficit(15, Map.of(Difficulty.ADVANCED, 50L))).isEqualTo(10);
    assertThat(
            QuestionCoveragePlan.deficit(
                15,
                Map.of(
                    Difficulty.BEGINNER, 5L, Difficulty.INTERMEDIATE, 5L, Difficulty.ADVANCED, 5L)))
        .isZero();
  }

  @Test
  void tenQuestionsSpanAllLevelsAndCoreJavaFoundations() {
    var plan = QuestionCoveragePlan.create("Core Java", 10, Map.of());
    assertThat(plan).hasSize(10);
    assertThat(plan).filteredOn(s -> s.difficulty() == Difficulty.BEGINNER).hasSize(4);
    assertThat(plan).filteredOn(s -> s.difficulty() == Difficulty.INTERMEDIATE).hasSize(3);
    assertThat(plan).filteredOn(s -> s.difficulty() == Difficulty.ADVANCED).hasSize(3);
    assertThat(plan.getFirst().concept()).contains("String immutability");
    assertThat(plan.getFirst().questionType()).isEqualTo(QuestionType.FUNDAMENTAL);
    assertThat(plan)
        .noneSatisfy(s -> assertThat(s.questionType()).isEqualTo(QuestionType.SYSTEM_DESIGN));
    assertThat(plan.stream().map(QuestionCoveragePlan.Slot::concept)).doesNotHaveDuplicates();
  }

  @Test
  void replenishesMissingLevelsInsteadOfRepeatingOverrepresentedOnes() {
    var plan =
        QuestionCoveragePlan.create(
            "Core Java", 6, Map.of(Difficulty.ADVANCED, 10L, Difficulty.INTERMEDIATE, 10L));
    assertThat(plan).allSatisfy(s -> assertThat(s.difficulty()).isEqualTo(Difficulty.BEGINNER));
  }

  @Test
  void concurrencyIncludesDeadlockFoundationsAndDiagnosis() {
    var plan = QuestionCoveragePlan.create("Multithreading and Concurrency", 10, Map.of());
    assertThat(plan)
        .anySatisfy(
            s -> {
              assertThat(s.difficulty()).isEqualTo(Difficulty.BEGINNER);
              assertThat(s.concept()).contains("deadlock");
            });
    assertThat(plan)
        .anySatisfy(
            s -> {
              assertThat(s.difficulty()).isEqualTo(Difficulty.ADVANCED);
              assertThat(s.concept()).contains("thread dump");
            });
  }

  @Test
  void refusesAUniformAdvancedBatchWhenFoundationsWereRequested() {
    var plan = QuestionCoveragePlan.create("Core Java", 10, Map.of());
    var draft =
        new BankDtos.Draft(
            "How do class initialization and static state interact in Java?",
            1L,
            "Classes",
            Difficulty.ADVANCED,
            QuestionType.FUNDAMENTAL,
            List.of("java"),
            "Class initialization");
    assertThatThrownBy(
            () ->
                QuestionCoveragePlan.validate(
                    new BankDtos.Generated(Collections.nCopies(10, draft)),
                    Map.of("requestedQuestions", plan)))
        .hasMessageContaining("difficulty mix");
  }
}
