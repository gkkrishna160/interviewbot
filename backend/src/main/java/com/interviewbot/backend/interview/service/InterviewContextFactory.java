package com.interviewbot.backend.interview.service;

import com.interviewbot.backend.ai.dto.Difficulty;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewContext;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.dto.QuestionHistoryItem;
import com.interviewbot.backend.interview.entity.CandidateAnswer;
import com.interviewbot.backend.interview.entity.EvaluationEntity;
import com.interviewbot.backend.interview.entity.Interview;
import com.interviewbot.backend.interview.entity.InterviewQuestion;
import com.interviewbot.backend.interview.repository.CandidateAnswerRepository;
import com.interviewbot.backend.interview.repository.EvaluationRepository;
import com.interviewbot.backend.interview.repository.InterviewQuestionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InterviewContextFactory {
    private final InterviewQuestionRepository questionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final EvaluationRepository evaluationRepository;
    private final InterviewMapper mapper;

    public InterviewContextFactory(
        InterviewQuestionRepository questionRepository,
        CandidateAnswerRepository answerRepository,
        EvaluationRepository evaluationRepository,
        InterviewMapper mapper
    ) {
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evaluationRepository = evaluationRepository;
        this.mapper = mapper;
    }

    public InterviewContext build(Interview interview) {
        List<InterviewQuestion> questions = questionRepository.findByInterviewIdOrderBySequenceAsc(interview.getId());
        Question current = questions.isEmpty() ? null : mapper.toQuestionDto(questions.getLast());
        List<QuestionHistoryItem> history = questions.stream()
            .map(this::toHistory)
            .toList();

        Difficulty difficulty = current == null ? Difficulty.MEDIUM : current.difficulty();
        return new InterviewContext(
            interview.getId(),
            interview.getCompany(),
            interview.getRole(),
            interview.getExperienceLevel(),
            interview.getLanguages(),
            interview.getFrameworks(),
            interview.getTopics(),
            interview.getDurationMinutes(),
            current,
            history,
            difficulty,
            remainingMinutes(interview)
        );
    }

    private QuestionHistoryItem toHistory(InterviewQuestion question) {
        CandidateAnswer answer = answerRepository.findTopByQuestionIdOrderByAnsweredAtDesc(question.getId()).orElse(null);
        Evaluation evaluation = null;
        String answerText = null;
        if (answer != null) {
            answerText = answer.getAnswer();
            EvaluationEntity entity = evaluationRepository.findByAnswerId(answer.getId()).orElse(null);
            if (entity != null) {
                evaluation = new Evaluation(
                    entity.getRelevanceScore(),
                    entity.getTechnicalScore(),
                    entity.getCorrectnessScore(),
                    entity.getFeedback(),
                    entity.isAnsweredCorrectly(),
                    entity.isNeedsRetry(),
                    entity.getFollowUpQuestion()
                );
            }
        }
        return new QuestionHistoryItem(question.getQuestion(), question.getTopic(), question.getDifficulty(), answerText, evaluation);
    }

    private long remainingMinutes(Interview interview) {
        if (interview.getStartedAt() == null) {
            return interview.getDurationMinutes();
        }
        long elapsed = Duration.between(interview.getStartedAt(), Instant.now()).toMinutes();
        return Math.max(0, interview.getDurationMinutes() - elapsed);
    }
}
