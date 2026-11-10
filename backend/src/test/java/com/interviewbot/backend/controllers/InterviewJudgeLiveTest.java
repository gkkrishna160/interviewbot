package com.interviewbot.backend.controllers;

import static org.assertj.core.api.Assertions.*;

import com.interviewbot.backend.dtos.MockDtos.*;
import com.interviewbot.backend.enums.Area;
import com.interviewbot.backend.enums.BankTypes.*;
import com.interviewbot.backend.security.QuestionTickets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real local Ollama, real HTTP assessment, isolated in-memory database; no bank writes. */
@EnabledIfEnvironmentVariable(named = "INTERVIEW_JUDGE_LIVE_TEST", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.config.import=",
      "spring.datasource.url=jdbc:h2:mem:livejudge;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
      "question-bank.provider=ollama",
      "question-bank.base-url=http://localhost:11434",
      "logging.file.name=target/live-judge.log"
    })
@ActiveProfiles("mock-test")
class InterviewJudgeLiveTest {
  @Autowired TestRestTemplate http;
  @Autowired QuestionTickets tickets;

  @Test
  void assessesKnownCorrectAndUnknownAnswersThroughTheActualModel() {
    assess(
        "Why is String immutable in Java, and what benefits does this provide?",
        "String values cannot change after construction. Immutability makes shared and pooled"
            + " strings safe, keeps hash codes stable for map keys, and helps thread safety."
            + " Concatenation creates a new string; StringBuilder is useful for repeated changes.",
        Difficulty.BEGINNER,
        7,
        10);
    assess(
        "What conditions must hold for Java threads to deadlock, and how can lock ordering prevent"
            + " it?",
        "Deadlock requires mutual exclusion, hold and wait, no preemption and a circular wait. If"
            + " every thread acquires locks in the same global order, a circular wait cannot form."
            + " Timeouts and avoiding nested locking also help. A thread dump can reveal the"
            + " waiting cycle.",
        Difficulty.ADVANCED,
        7,
        10);
    assess(
        "Why is String immutable in Java, and what benefits does this provide?",
        "I do not know the answer yet.",
        Difficulty.BEGINNER,
        0,
        2);
  }

  private void assess(
      String question, String answer, Difficulty difficulty, double min, double max) {
    var signed =
        tickets.sign(
            new Data(
                "live-check",
                Area.BACKEND,
                question,
                "Core Java",
                null,
                difficulty,
                QuestionType.FUNDAMENTAL,
                "TEST"));
    long start = System.nanoTime();
    var response =
        http.postForEntity(
            "/api/interview/assess", new Assessment(signed.token(), answer), Judgment.class);
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    var result = response.getBody();
    assertThat(result.score())
        .as("Feedback: %s; reference: %s", result.feedback(), result.modelAnswer())
        .isBetween(min, max);
    assertThat(result.feedback()).isNotBlank();
    assertThat(result.modelAnswer()).isNotBlank();
    System.out.printf(
        "LIVE_JUDGE difficulty=%s elapsedMs=%d score=%.1f%n", difficulty, elapsed, result.score());
  }
}
