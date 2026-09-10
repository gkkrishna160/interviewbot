package com.interviewbot.backend.ai.prompt;

import com.interviewbot.backend.ai.dto.Question;
import org.springframework.stereotype.Component;

@Component
public class EvaluationPromptBuilder {
    public String build(Question question, String candidateAnswer) {
        return """
            You are a strict technical interview evaluator.
            Return only valid JSON with this schema:
            {
              "relevanceScore": 0,
              "technicalScore": 0,
              "correctnessScore": 0,
              "feedback": "string",
              "answeredCorrectly": false,
              "needsRetry": true,
              "followUpQuestion": "string or null"
            }

            Scoring rules:
            - Scores are integers from 0 to 10.
            - Prioritize relevance before technical correctness.
            - If the answer is about a different topic than the question, relevanceScore must be 0 or 1.
            - A technically valid but irrelevant answer is not correct.
            - needsRetry should be true when relevanceScore < 5 or correctnessScore < 6.
            - Provide a focused followUpQuestion when needsRetry is true.

            Question: %s
            Topic: %s
            Difficulty: %s
            Expected concepts: %s
            Candidate answer: %s
            """.formatted(
            question.question(),
            question.topic(),
            question.difficulty(),
            question.expectedConcepts(),
            candidateAnswer
        );
    }
}
