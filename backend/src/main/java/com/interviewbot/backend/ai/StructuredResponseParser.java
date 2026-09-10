package com.interviewbot.backend.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewPlan;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.common.exception.LLMException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StructuredResponseParser {
    private final ObjectMapper objectMapper;

    public StructuredResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Question parseQuestion(String content) {
        Question question = parse(content, Question.class);
        if (blank(question.question()) || blank(question.topic()) || question.difficulty() == null) {
            throw new LLMException("Malformed LLM question response");
        }
        List<String> concepts = question.expectedConcepts() == null ? List.of() : question.expectedConcepts();
        return new Question(question.question(), question.topic(), question.difficulty(), question.questionType(), concepts);
    }

    public Evaluation parseEvaluation(String content) {
        Evaluation evaluation = parse(content, Evaluation.class);
        validateScore(evaluation.relevanceScore(), "relevanceScore");
        validateScore(evaluation.technicalScore(), "technicalScore");
        validateScore(evaluation.correctnessScore(), "correctnessScore");
        if (blank(evaluation.feedback())) {
            throw new LLMException("Malformed LLM evaluation response");
        }
        boolean retryRequiredByScores = evaluation.relevanceScore() < 5 || evaluation.correctnessScore() < 6;
        if (retryRequiredByScores && !evaluation.needsRetry()) {
            throw new LLMException("Malformed LLM evaluation response: low scores require needsRetry=true");
        }
        if (evaluation.needsRetry() && blank(evaluation.followUpQuestion())) {
            throw new LLMException("Malformed LLM evaluation response: retry requires a follow-up question");
        }
        return evaluation;
    }

    public InterviewPlan parsePlan(String content) {
        InterviewPlan plan = parse(content, InterviewPlan.class);
        if (plan.topics() == null || plan.topics().isEmpty() || plan.startingDifficulty() == null || plan.estimatedQuestionCount() < 1) {
            throw new LLMException("Malformed LLM interview plan response");
        }
        return plan;
    }

    private <T> T parse(String content, Class<T> type) {
        String json = extractJson(content);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new LLMException("LLM returned invalid JSON", ex);
        }
    }

    private String extractJson(String content) {
        if (content == null) {
            throw new LLMException("LLM returned an empty response");
        }
        String withoutThinking = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = withoutThinking.indexOf('{');
        int end = withoutThinking.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LLMException("LLM response did not contain a JSON object");
        }
        return withoutThinking.substring(start, end + 1);
    }

    private void validateScore(int score, String field) {
        if (score < 0 || score > 10) {
            throw new LLMException("LLM evaluation field out of range: " + field);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
