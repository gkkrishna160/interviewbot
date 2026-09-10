package com.interviewbot.backend.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.interviewbot.backend.ai.LLMService;
import com.interviewbot.backend.ai.dto.Difficulty;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewPlan;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.dto.QuestionType;
import com.interviewbot.backend.common.exception.InvalidInterviewStateException;
import com.interviewbot.backend.interview.dto.CreateInterviewRequest;
import com.interviewbot.backend.interview.entity.InterviewStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class InterviewServiceTest {
    @Autowired
    private InterviewService interviewService;

    @MockBean
    private LLMService llmService;

    @Test
    void answerSubmissionEvaluatesAndCreatesFollowUpWhenRetryNeeded() {
        when(llmService.generateQuestion(any()))
            .thenReturn(new Question(
                "Explain how ThreadPoolExecutor works in Java.",
                "Java Concurrency",
                Difficulty.MEDIUM,
                QuestionType.PRIMARY,
                List.of("corePoolSize", "workQueue")
            ))
            .thenReturn(new Question(
                "How does ThreadPoolExecutor decide whether to queue a task or create another worker thread?",
                "Java Concurrency",
                Difficulty.MEDIUM,
                QuestionType.PRIMARY,
                List.of("corePoolSize", "maximumPoolSize", "workQueue")
            ));
        when(llmService.evaluateAnswer(any(), eq("HashMap uses buckets."))).thenReturn(new Evaluation(
            0,
            3,
            1,
            "The answer is about HashMap, not ThreadPoolExecutor.",
            false,
            true,
            "Let's try again: how does ThreadPoolExecutor manage worker threads and queued tasks?"
        ));
        when(llmService.createInterviewPlan(any())).thenReturn(new InterviewPlan(List.of("Java Concurrency"), Difficulty.MEDIUM, 3));

        var created = interviewService.create(request());
        interviewService.start(created.id());

        var response = interviewService.submitAnswer(created.id(), "HashMap uses buckets.");

        assertThat(response.evaluation().relevanceScore()).isZero();
        assertThat(response.evaluation().needsRetry()).isTrue();
        assertThat(response.nextQuestion()).isNotNull();
        assertThat(response.nextQuestion().questionType()).isEqualTo(QuestionType.FOLLOW_UP);
        assertThat(response.nextQuestion().question()).contains("queue a task");
        assertThat(response.status()).isEqualTo(InterviewStatus.WAITING_FOR_ANSWER);
    }

    @Test
    void rejectsAnswerBeforeInterviewIsStarted() {
        var created = interviewService.create(request());

        assertThatThrownBy(() -> interviewService.submitAnswer(created.id(), "answer"))
            .isInstanceOf(InvalidInterviewStateException.class)
            .hasMessageContaining("not waiting");
    }

    private CreateInterviewRequest request() {
        return new CreateInterviewRequest(
            "Virtusa",
            "Backend Developer",
            "5+ years",
            List.of("Java"),
            List.of("Spring Boot"),
            List.of("Core Java", "Concurrency", "Spring Boot"),
            45
        );
    }
}
