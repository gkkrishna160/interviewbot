package com.interviewbot.backend.interview.service;

import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.dto.QuestionType;
import com.interviewbot.backend.interview.dto.EvaluationResponse;
import com.interviewbot.backend.interview.dto.InterviewResponse;
import com.interviewbot.backend.interview.dto.QuestionResponse;
import com.interviewbot.backend.interview.entity.EvaluationEntity;
import com.interviewbot.backend.interview.entity.Interview;
import com.interviewbot.backend.interview.entity.InterviewQuestion;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InterviewMapper {
    InterviewResponse toResponse(Interview interview) {
        return new InterviewResponse(
            interview.getId(),
            interview.getCompany(),
            interview.getRole(),
            interview.getExperienceLevel(),
            new ArrayList<>(interview.getLanguages()),
            new ArrayList<>(interview.getFrameworks()),
            new ArrayList<>(interview.getTopics()),
            interview.getDurationMinutes(),
            interview.getStatus(),
            interview.getStartedAt(),
            interview.getCompletedAt(),
            interview.getCreatedAt()
        );
    }

    QuestionResponse toResponse(InterviewQuestion question) {
        if (question == null) {
            return null;
        }
        return new QuestionResponse(
            question.getId(),
            question.getQuestion(),
            question.getTopic(),
            question.getDifficulty(),
            question.getQuestionType(),
            question.getSequence(),
            new ArrayList<>(question.getExpectedConcepts())
        );
    }

    EvaluationResponse toResponse(EvaluationEntity evaluation) {
        return new EvaluationResponse(
            evaluation.getId(),
            evaluation.getRelevanceScore(),
            evaluation.getTechnicalScore(),
            evaluation.getCorrectnessScore(),
            evaluation.getFeedback(),
            evaluation.isAnsweredCorrectly(),
            evaluation.isNeedsRetry(),
            evaluation.getFollowUpQuestion()
        );
    }

    Question toQuestionDto(InterviewQuestion question) {
        return new Question(
            question.getQuestion(),
            question.getTopic(),
            question.getDifficulty(),
            question.getQuestionType(),
            new ArrayList<>(question.getExpectedConcepts())
        );
    }

    InterviewQuestion toEntity(Interview interview, Question question, int sequence) {
        InterviewQuestion entity = new InterviewQuestion();
        entity.setInterview(interview);
        entity.setQuestion(question.question());
        entity.setTopic(question.topic());
        entity.setDifficulty(question.difficulty());
        entity.setQuestionType(question.questionType() == null ? QuestionType.PRIMARY : question.questionType());
        entity.setSequence(sequence);
        entity.setExpectedConcepts(question.expectedConcepts() == null ? List.of() : new ArrayList<>(question.expectedConcepts()));
        return entity;
    }

    EvaluationEntity toEntity(com.interviewbot.backend.interview.entity.CandidateAnswer answer, Evaluation evaluation) {
        EvaluationEntity entity = new EvaluationEntity();
        entity.setAnswer(answer);
        entity.setRelevanceScore(evaluation.relevanceScore());
        entity.setTechnicalScore(evaluation.technicalScore());
        entity.setCorrectnessScore(evaluation.correctnessScore());
        entity.setFeedback(evaluation.feedback());
        entity.setAnsweredCorrectly(evaluation.answeredCorrectly());
        entity.setNeedsRetry(evaluation.needsRetry());
        entity.setFollowUpQuestion(evaluation.followUpQuestion());
        return entity;
    }
}
