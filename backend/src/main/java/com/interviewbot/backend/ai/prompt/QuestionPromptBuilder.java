package com.interviewbot.backend.ai.prompt;

import com.interviewbot.backend.ai.dto.InterviewContext;
import org.springframework.stereotype.Component;

@Component
public class QuestionPromptBuilder {
    public String build(InterviewContext context) {
        return """
            You are a senior technical interviewer. Generate exactly one software engineering interview question.
            Return only valid JSON with this schema:
            {
              "question": "string",
              "topic": "string",
              "difficulty": "EASY|MEDIUM|HARD",
              "questionType": "PRIMARY",
              "expectedConcepts": ["string"]
            }

            Interview context:
            Company: %s
            Role: %s
            Experience: %s
            Languages: %s
            Frameworks: %s
            Selected topics: %s
            Duration minutes: %d
            Current difficulty: %s
            Remaining minutes: %d
            Previous questions: %s

            Choose a topic from the selected topics when possible. Avoid repeating previous questions.
            If the latest evaluation shows an incorrect or irrelevant answer, generate a new focused follow-up question
            that tests the missing concept without repeating the exact previous question.
            """.formatted(
            context.company(),
            context.role(),
            context.experienceLevel(),
            context.languages(),
            context.frameworks(),
            context.topics(),
            context.durationMinutes(),
            context.currentDifficulty(),
            context.remainingMinutes(),
            context.previousQuestions()
        );
    }
}
