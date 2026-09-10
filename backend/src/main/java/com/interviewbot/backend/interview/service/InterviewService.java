package com.interviewbot.backend.interview.service;

import com.interviewbot.backend.ai.LLMService;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.dto.QuestionType;
import com.interviewbot.backend.common.exception.InterviewNotFoundException;
import com.interviewbot.backend.common.exception.InvalidInterviewStateException;
import com.interviewbot.backend.interview.dto.CreateInterviewRequest;
import com.interviewbot.backend.interview.dto.EvaluationResponse;
import com.interviewbot.backend.interview.dto.InterviewReportResponse;
import com.interviewbot.backend.interview.dto.InterviewResponse;
import com.interviewbot.backend.interview.dto.QuestionResponse;
import com.interviewbot.backend.interview.dto.ReportQuestionItem;
import com.interviewbot.backend.interview.dto.SubmitAnswerResponse;
import com.interviewbot.backend.interview.entity.CandidateAnswer;
import com.interviewbot.backend.interview.entity.EvaluationEntity;
import com.interviewbot.backend.interview.entity.Interview;
import com.interviewbot.backend.interview.entity.InterviewQuestion;
import com.interviewbot.backend.interview.entity.InterviewStatus;
import com.interviewbot.backend.interview.repository.CandidateAnswerRepository;
import com.interviewbot.backend.interview.repository.EvaluationRepository;
import com.interviewbot.backend.interview.repository.InterviewQuestionRepository;
import com.interviewbot.backend.interview.repository.InterviewRepository;
import com.interviewbot.backend.interview.state.InterviewStateMachine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewService {
    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final InterviewRepository interviewRepository;
    private final InterviewQuestionRepository questionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final EvaluationRepository evaluationRepository;
    private final LLMService llmService;
    private final InterviewStateMachine stateMachine;
    private final InterviewContextFactory contextFactory;
    private final InterviewMapper mapper;
    private final int configuredMaxQuestions;

    public InterviewService(
        InterviewRepository interviewRepository,
        InterviewQuestionRepository questionRepository,
        CandidateAnswerRepository answerRepository,
        EvaluationRepository evaluationRepository,
        LLMService llmService,
        InterviewStateMachine stateMachine,
        InterviewContextFactory contextFactory,
        InterviewMapper mapper,
        @Value("${interview.max-questions:6}") int configuredMaxQuestions
    ) {
        this.interviewRepository = interviewRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evaluationRepository = evaluationRepository;
        this.llmService = llmService;
        this.stateMachine = stateMachine;
        this.contextFactory = contextFactory;
        this.mapper = mapper;
        this.configuredMaxQuestions = configuredMaxQuestions;
    }

    @Transactional
    public InterviewResponse create(CreateInterviewRequest request) {
        log.info(
            "Creating interview: company={}, role={}, experience={}, durationMinutes={}, languages={}, frameworks={}, topics={}",
            request.company(),
            request.role(),
            request.experienceLevel(),
            request.durationMinutes(),
            request.languages(),
            request.frameworks(),
            request.topics()
        );
        Interview interview = new Interview();
        interview.setCompany(request.company());
        interview.setRole(request.role());
        interview.setExperienceLevel(request.experienceLevel());
        interview.setDurationMinutes(request.durationMinutes());
        interview.setLanguages(new ArrayList<>(request.languages()));
        interview.setFrameworks(request.frameworks() == null ? List.of() : new ArrayList<>(request.frameworks()));
        interview.setTopics(new ArrayList<>(request.topics()));
        Interview saved = interviewRepository.save(interview);
        log.info("Created interview id={} status={}", saved.getId(), saved.getStatus());
        return mapper.toResponse(saved);
    }

    @Transactional
    public QuestionResponse start(Long id) {
        long started = System.nanoTime();
        log.info("Starting interview id={}", id);
        Interview interview = find(id);
        log.info("Loaded interview id={} currentStatus={}", id, interview.getStatus());
        if (interview.getStatus() == InterviewStatus.CREATED) {
            transition(interview, InterviewStatus.READY);
            interview.setStartedAt(Instant.now());
            log.info("Interview id={} marked startedAt={}", id, interview.getStartedAt());
        }
        if (interview.getStatus() != InterviewStatus.READY) {
            throw new InvalidInterviewStateException("Interview must be CREATED or READY to start");
        }
        QuestionResponse question = createAndPersistQuestion(interview, QuestionType.PRIMARY);
        transition(interview, InterviewStatus.ASKING);
        transition(interview, InterviewStatus.WAITING_FOR_ANSWER);
        log.info(
            "Started interview id={} firstQuestionId={} topic={} difficulty={} elapsedMs={}",
            id,
            question.id(),
            question.topic(),
            question.difficulty(),
            elapsedMs(started)
        );
        return question;
    }

    @Transactional(readOnly = true)
    public InterviewResponse get(Long id) {
        return mapper.toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public QuestionResponse currentQuestion(Long id) {
        find(id);
        return questionRepository.findTopByInterviewIdOrderBySequenceDesc(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> new InvalidInterviewStateException("No active question for interview: " + id));
    }

    @Transactional
    public SubmitAnswerResponse submitAnswer(Long id, String answerText) {
        long started = System.nanoTime();
        log.info("Submitting answer for interview id={} answerLength={}", id, answerText.length());
        Interview interview = find(id);
        log.info("Loaded interview id={} currentStatus={}", id, interview.getStatus());
        if (interview.getStatus() != InterviewStatus.WAITING_FOR_ANSWER) {
            throw new InvalidInterviewStateException("Interview is not waiting for an answer");
        }

        InterviewQuestion question = questionRepository.findTopByInterviewIdOrderBySequenceDesc(id)
            .orElseThrow(() -> new InvalidInterviewStateException("No active question for interview: " + id));
        log.info("Evaluating answer for interview id={} questionId={} sequence={} topic={}", id, question.getId(), question.getSequence(), question.getTopic());

        transition(interview, InterviewStatus.EVALUATING);
        CandidateAnswer answer = new CandidateAnswer();
        answer.setQuestion(question);
        answer.setAnswer(answerText);
        answerRepository.save(answer);
        log.info("Saved answer id={} for interview id={} questionId={}", answer.getId(), id, question.getId());

        long llmStarted = System.nanoTime();
        Evaluation evaluation = llmService.evaluateAnswer(mapper.toQuestionDto(question), answerText);
        log.info(
            "LLM evaluation completed for interview id={} questionId={} relevance={} technical={} correctness={} needsRetry={} elapsedMs={}",
            id,
            question.getId(),
            evaluation.relevanceScore(),
            evaluation.technicalScore(),
            evaluation.correctnessScore(),
            evaluation.needsRetry(),
            elapsedMs(llmStarted)
        );
        EvaluationEntity savedEvaluation = evaluationRepository.save(mapper.toEntity(answer, evaluation));
        log.info("Saved evaluation id={} for answer id={}", savedEvaluation.getId(), answer.getId());

        QuestionResponse nextQuestion = null;
        if (shouldComplete(interview)) {
            log.info("Interview id={} reached completion criteria after questionId={}", id, question.getId());
            completeFrom(interview, InterviewStatus.EVALUATING);
        } else if (evaluation.needsRetry()) {
            log.info("Interview id={} needs retry; generating a new follow-up question", id);
            transition(interview, InterviewStatus.FOLLOW_UP);
            nextQuestion = createAndPersistQuestion(interview, QuestionType.FOLLOW_UP);
            transition(interview, InterviewStatus.ASKING);
            transition(interview, InterviewStatus.WAITING_FOR_ANSWER);
        } else {
            log.info("Interview id={} answer accepted; generating next primary question", id);
            nextQuestion = createAndPersistQuestion(interview, QuestionType.PRIMARY);
            transition(interview, InterviewStatus.ASKING);
            transition(interview, InterviewStatus.WAITING_FOR_ANSWER);
        }

        log.info(
            "Submit answer flow finished for interview id={} status={} nextQuestionId={} elapsedMs={}",
            id,
            interview.getStatus(),
            nextQuestion == null ? null : nextQuestion.id(),
            elapsedMs(started)
        );
        return new SubmitAnswerResponse(mapper.toResponse(savedEvaluation), nextQuestion, interview.getStatus());
    }

    @Transactional
    public InterviewResponse complete(Long id) {
        log.info("Completing interview id={}", id);
        Interview interview = find(id);
        if (interview.getStatus() == InterviewStatus.COMPLETED) {
            log.info("Interview id={} is already completed", id);
            return mapper.toResponse(interview);
        }
        completeFrom(interview, interview.getStatus());
        log.info("Completed interview id={} completedAt={}", id, interview.getCompletedAt());
        return mapper.toResponse(interview);
    }

    @Transactional(readOnly = true)
    public InterviewReportResponse report(Long id) {
        log.info("Building report for interview id={}", id);
        find(id);
        List<InterviewQuestion> questions = questionRepository.findByInterviewIdOrderBySequenceAsc(id);
        List<ReportQuestionItem> items = questions.stream()
            .map(this::toReportItem)
            .toList();

        List<EvaluationResponse> evaluations = items.stream()
            .map(ReportQuestionItem::evaluation)
            .filter(evaluation -> evaluation != null)
            .toList();

        double overall = evaluations.stream()
            .mapToDouble(e -> (e.relevanceScore() + e.technicalScore() + e.correctnessScore()) / 3.0)
            .average()
            .orElse(0.0);

        Map<String, Double> topicPerformance = items.stream()
            .filter(item -> item.evaluation() != null)
            .collect(Collectors.groupingBy(
                item -> item.question().topic(),
                LinkedHashMap::new,
                Collectors.averagingDouble(item -> item.evaluation().correctnessScore())
            ));

        InterviewReportResponse report = new InterviewReportResponse(
            id,
            round(overall),
            topicPerformance,
            buildStrengths(topicPerformance),
            buildWeaknesses(topicPerformance),
            buildRecommendations(topicPerformance, evaluations),
            items
        );
        log.info("Built report for interview id={} questionCount={} evaluationCount={} overallScore={}", id, questions.size(), evaluations.size(), report.overallScore());
        return report;
    }

    private QuestionResponse createAndPersistQuestion(Interview interview, QuestionType type) {
        long started = System.nanoTime();
        log.info("Generating {} question for interview id={}", type, interview.getId());
        Question generated = llmService.generateQuestion(contextFactory.build(interview));
        log.info(
            "LLM generated {} question for interview id={} topic={} difficulty={} elapsedMs={}",
            type,
            interview.getId(),
            generated.topic(),
            generated.difficulty(),
            elapsedMs(started)
        );
        Question normalized = new Question(
            generated.question(),
            generated.topic(),
            generated.difficulty(),
            type,
            generated.expectedConcepts() == null ? List.of() : generated.expectedConcepts()
        );
        InterviewQuestion saved = questionRepository.save(mapper.toEntity(interview, normalized, nextSequence(interview)));
        log.info("Saved {} question id={} sequence={} interviewId={}", type, saved.getId(), saved.getSequence(), interview.getId());
        return mapper.toResponse(saved);
    }

    private int nextSequence(Interview interview) {
        return (int) questionRepository.countByInterviewId(interview.getId()) + 1;
    }

    private boolean shouldComplete(Interview interview) {
        int maxByDuration = Math.max(3, Math.min(configuredMaxQuestions, Math.max(1, interview.getDurationMinutes() / 8)));
        long questionCount = questionRepository.countByInterviewId(interview.getId());
        long remainingMinutes = contextFactory.build(interview).remainingMinutes();
        boolean complete = questionCount >= maxByDuration || remainingMinutes <= 0;
        log.info(
            "Completion check interview id={} questionCount={} maxQuestions={} remainingMinutes={} complete={}",
            interview.getId(),
            questionCount,
            maxByDuration,
            remainingMinutes,
            complete
        );
        return complete;
    }

    private ReportQuestionItem toReportItem(InterviewQuestion question) {
        CandidateAnswer answer = answerRepository.findTopByQuestionIdOrderByAnsweredAtDesc(question.getId()).orElse(null);
        EvaluationResponse evaluation = null;
        if (answer != null) {
            evaluation = evaluationRepository.findByAnswerId(answer.getId()).map(mapper::toResponse).orElse(null);
        }
        return new ReportQuestionItem(mapper.toResponse(question), answer == null ? null : answer.getAnswer(), evaluation);
    }

    private List<String> buildStrengths(Map<String, Double> topicPerformance) {
        return topicPerformance.entrySet().stream()
            .filter(entry -> entry.getValue() >= 7.0)
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .map(entry -> entry.getKey() + " (" + round(entry.getValue()) + "/10)")
            .toList();
    }

    private List<String> buildWeaknesses(Map<String, Double> topicPerformance) {
        return topicPerformance.entrySet().stream()
            .filter(entry -> entry.getValue() < 6.0)
            .sorted(Map.Entry.comparingByValue())
            .map(entry -> entry.getKey() + " (" + round(entry.getValue()) + "/10)")
            .toList();
    }

    private List<String> buildRecommendations(Map<String, Double> topicPerformance, List<EvaluationResponse> evaluations) {
        List<String> recommendations = new ArrayList<>();
        buildWeaknesses(topicPerformance).forEach(topic -> recommendations.add("Review " + topic + " with hands-on examples."));
        long lowRelevance = evaluations.stream().filter(evaluation -> evaluation.relevanceScore() < 5).count();
        if (lowRelevance > 0) {
            recommendations.add("Listen for the exact topic being asked before answering; relevance is scored separately from technical depth.");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("Continue practicing scenario-based explanations and trade-off discussions.");
        }
        return recommendations;
    }

    private void completeFrom(Interview interview, InterviewStatus from) {
        if (from != interview.getStatus()) {
            interview.setStatus(from);
        }
        transition(interview, InterviewStatus.COMPLETED);
        interview.setCompletedAt(Instant.now());
    }

    private void transition(Interview interview, InterviewStatus to) {
        InterviewStatus from = interview.getStatus();
        interview.setStatus(stateMachine.transition(interview.getStatus(), to));
        log.info("Interview id={} state transition {} -> {}", interview.getId(), from, to);
    }

    private Interview find(Long id) {
        return interviewRepository.findById(id).orElseThrow(() -> new InterviewNotFoundException(id));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
