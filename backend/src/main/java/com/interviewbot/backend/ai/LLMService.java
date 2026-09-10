package com.interviewbot.backend.ai;

import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewContext;
import com.interviewbot.backend.ai.dto.InterviewPlan;
import com.interviewbot.backend.ai.dto.Question;

public interface LLMService {
    Question generateQuestion(InterviewContext context);

    Evaluation evaluateAnswer(Question question, String candidateAnswer);

    InterviewPlan createInterviewPlan(InterviewContext context);
}
