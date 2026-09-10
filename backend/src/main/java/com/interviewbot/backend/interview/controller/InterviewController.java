package com.interviewbot.backend.interview.controller;

import com.interviewbot.backend.interview.dto.CreateInterviewRequest;
import com.interviewbot.backend.interview.dto.InterviewReportResponse;
import com.interviewbot.backend.interview.dto.InterviewResponse;
import com.interviewbot.backend.interview.dto.QuestionResponse;
import com.interviewbot.backend.interview.dto.SubmitAnswerRequest;
import com.interviewbot.backend.interview.dto.SubmitAnswerResponse;
import com.interviewbot.backend.interview.service.InterviewService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewResponse create(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("HTTP POST /api/interviews");
        return interviewService.create(request);
    }

    @PostMapping("/{id}/start")
    public QuestionResponse start(@PathVariable Long id) {
        log.info("HTTP POST /api/interviews/{}/start", id);
        return interviewService.start(id);
    }

    @GetMapping("/{id}/current-question")
    public QuestionResponse currentQuestion(@PathVariable Long id) {
        log.info("HTTP GET /api/interviews/{}/current-question", id);
        return interviewService.currentQuestion(id);
    }

    @PostMapping("/{id}/answers")
    public SubmitAnswerResponse submitAnswer(@PathVariable Long id, @Valid @RequestBody SubmitAnswerRequest request) {
        log.info("HTTP POST /api/interviews/{}/answers answerLength={}", id, request.answer().length());
        return interviewService.submitAnswer(id, request.answer());
    }

    @GetMapping("/{id}")
    public InterviewResponse get(@PathVariable Long id) {
        log.info("HTTP GET /api/interviews/{}", id);
        return interviewService.get(id);
    }

    @PostMapping("/{id}/complete")
    public InterviewResponse complete(@PathVariable Long id) {
        log.info("HTTP POST /api/interviews/{}/complete", id);
        return interviewService.complete(id);
    }

    @GetMapping("/{id}/report")
    public InterviewReportResponse report(@PathVariable Long id) {
        log.info("HTTP GET /api/interviews/{}/report", id);
        return interviewService.report(id);
    }
}
