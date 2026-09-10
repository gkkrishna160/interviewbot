package com.interviewbot.backend.ai.prompt;

import com.interviewbot.backend.ai.dto.InterviewContext;
import org.springframework.stereotype.Component;

@Component
public class InterviewPlanPromptBuilder {
    public String build(InterviewContext context) {
        return """
            You are designing a focused technical interview plan.
            Return only valid JSON with this schema:
            {
              "topics": ["string"],
              "startingDifficulty": "EASY|MEDIUM|HARD",
              "estimatedQuestionCount": 4
            }

            Role: %s
            Experience: %s
            Languages: %s
            Frameworks: %s
            Selected topics: %s
            Duration minutes: %d
            """.formatted(
            context.role(),
            context.experienceLevel(),
            context.languages(),
            context.frameworks(),
            context.topics(),
            context.durationMinutes()
        );
    }
}
