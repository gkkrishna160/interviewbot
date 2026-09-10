package com.interviewbot.backend.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewbot.backend.ai.StructuredResponseParser;
import com.interviewbot.backend.ai.dto.Difficulty;
import com.interviewbot.backend.common.exception.LLMException;
import org.junit.jupiter.api.Test;

class StructuredResponseParserTest {
    private final StructuredResponseParser parser = new StructuredResponseParser(new ObjectMapper());

    @Test
    void parsesQuestionFromStructuredJson() {
        var question = parser.parseQuestion("""
            <think>notes</think>
            {"question":"Explain ThreadPoolExecutor","topic":"Java Concurrency","difficulty":"MEDIUM","questionType":"PRIMARY","expectedConcepts":["corePoolSize"]}
            """);

        assertThat(question.topic()).isEqualTo("Java Concurrency");
        assertThat(question.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(question.expectedConcepts()).containsExactly("corePoolSize");
    }

    @Test
    void rejectsEvaluationWithInvalidScore() {
        assertThatThrownBy(() -> parser.parseEvaluation("""
            {"relevanceScore":12,"technicalScore":5,"correctnessScore":5,"feedback":"bad","answeredCorrectly":false,"needsRetry":true,"followUpQuestion":"retry"}
            """))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void rejectsEvaluationWhenLowScoresDoNotRequestRetry() {
        assertThatThrownBy(() -> parser.parseEvaluation("""
            {"relevanceScore":1,"technicalScore":8,"correctnessScore":8,"feedback":"irrelevant","answeredCorrectly":true,"needsRetry":false,"followUpQuestion":null}
            """))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("needsRetry=true");
    }
}
