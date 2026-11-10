package com.interviewbot.backend.controllers;

import static com.interviewbot.backend.dtos.BankDtos.*;
import static com.interviewbot.backend.enums.BankTypes.*;

import com.interviewbot.backend.agents.QuestionBankWorkflow;
import com.interviewbot.backend.services.QuestionBankService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.mode",
    havingValue = "generation_mode")
@RequestMapping("/api/question-bank")
public class QuestionBankController {
  private final QuestionBankService bank;
  private final QuestionBankWorkflow workflow;

  public QuestionBankController(QuestionBankService bank, QuestionBankWorkflow workflow) {
    this.bank = bank;
    this.workflow = workflow;
  }

  @GetMapping("/categories")
  public List<CategoryView> categories() {
    return bank.taxonomy();
  }

  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public CategoryView category(@Valid @RequestBody NameRequest request) {
    return bank.addCategory(request);
  }

  @PostMapping("/categories/{id}/topics")
  @ResponseStatus(HttpStatus.CREATED)
  public TopicView topic(@PathVariable long id, @Valid @RequestBody NameRequest request) {
    return bank.addTopic(id, request);
  }

  @GetMapping("/questions")
  public QuestionPage questions(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Long topicId,
      @RequestParam(required = false) Difficulty difficulty,
      @RequestParam(required = false) QuestionType questionType,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String tag,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return bank.browse(
        categoryId, topicId, difficulty, questionType, status, search, tag, page, size);
  }

  @GetMapping("/questions/{id}")
  public Detail detail(@PathVariable long id) {
    return bank.detail(id);
  }

  @PostMapping("/questions")
  @ResponseStatus(HttpStatus.CREATED)
  public QuestionView manual(@Valid @RequestBody Draft request) {
    return bank.addManual(request);
  }

  @PostMapping("/questions/{id}/requeue")
  public Detail requeue(@PathVariable long id, @Valid @RequestBody RequeueRequest request) {
    return bank.requeue(id, request);
  }

  @GetMapping("/dashboard")
  public Dashboard dashboard() {
    return bank.dashboard();
  }

  @GetMapping("/agents/executions")
  public List<ExecutionView> activity() {
    return bank.activity();
  }

  @PostMapping("/agents/run")
  public ResponseEntity<Map<String, Object>> run() {
    var result = workflow.trigger();
    return ResponseEntity.status(
            Boolean.TRUE.equals(result.get("accepted")) ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
        .body(result);
  }
}
