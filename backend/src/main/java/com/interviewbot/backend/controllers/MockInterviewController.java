package com.interviewbot.backend.controllers;

import static com.interviewbot.backend.dtos.MockDtos.*;

import com.interviewbot.backend.services.MockInterviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.mode",
    havingValue = "interview_mode",
    matchIfMissing = true)
@RequestMapping("/api/interview")
public class MockInterviewController {
  private final MockInterviewService service;

  public MockInterviewController(MockInterviewService service) {
    this.service = service;
  }

  @PostMapping("/questions")
  public Questions questions(@Valid @RequestBody Selection request) {
    return service.questions(request);
  }

  @PostMapping("/next-question")
  public Question next(@Valid @RequestBody Next request) {
    return service.next(request);
  }

  @PostMapping("/assess")
  public Judgment assess(@Valid @RequestBody Assessment request) {
    return service.assess(request);
  }
}
