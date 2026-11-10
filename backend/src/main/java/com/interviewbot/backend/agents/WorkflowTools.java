package com.interviewbot.backend.agents;

import static com.interviewbot.backend.dtos.BankDtos.*;

import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.services.QuestionBankService;
import com.interviewbot.backend.services.QuestionCoveragePlan;
import com.interviewbot.backend.services.WorkflowLease;
import java.util.*;

/** Per-cycle ADK capabilities: explicit delegation, fixed budgets and fenced persistence. */
public class WorkflowTools {
  private final QuestionBankService bank;
  private final BankAgentGateway agents;
  private final BankProperties properties;
  private final WorkflowLease.Lease lease;
  private final Set<Long> visited = new HashSet<>();
  private boolean generated;
  private int reviewCount;
  private int generatedCount;
  private int failures;
  private int toolCalls;
  private final int reviewRequestSize;
  private final Long assignedTopicId;

  public WorkflowTools(
      QuestionBankService bank,
      BankAgentGateway agents,
      BankProperties properties,
      WorkflowLease.Lease lease) {
    this(bank, agents, properties, lease, 1, null);
  }

  public WorkflowTools(
      QuestionBankService bank,
      BankAgentGateway agents,
      BankProperties properties,
      WorkflowLease.Lease lease,
      int reviewRequestSize,
      Long assignedTopicId) {
    this.bank = bank;
    this.agents = agents;
    this.properties = properties;
    this.lease = lease;
    this.reviewRequestSize = reviewRequestSize;
    this.assignedTopicId = assignedTopicId;
  }

  public synchronized Map<String, Object> generate_for_topic(long topicId) {
    agents.checkTool("generate_for_topic", Map.of("topicId", topicId));
    if (++toolCalls > properties.maxLlmCalls() * 2)
      return Map.of("error", "Tool call budget exhausted");
    if (generated) return Map.of("error", "Generation budget exhausted for this cycle");
    if (assignedTopicId != null && assignedTopicId != topicId)
      return Map.of(
          "error",
          "Use assignedTopicId to respect coverage rotation",
          "assignedTopicId",
          assignedTopicId);
    var gap =
        bank.coverage().stream().filter(t -> t.id().equals(topicId) && t.deficit() > 0).findFirst();
    if (gap.isEmpty()) return Map.of("error", "Topic has no coverage gap or is unavailable");
    generated = true;
    // Do not create an unbounded pending backlog when a provider repeatedly fails review.
    if (gap.get().pending() >= properties.minimumQuestionsPerTopic())
      return Map.of("error", "Review the pending backlog before generating more");
    int count =
        Math.min(
            properties.generationBatchSize(),
            Math.max(1, gap.get().deficit() - (int) gap.get().pending()));
    long batch = bank.reserveBatch(lease, topicId, count);
    Map<String, Object> task = bank.generationTask(topicId, count);
    for (int attempt = 1; attempt <= properties.retryLimit() + 1; attempt++) {
      long execution = bank.beginExecution(lease, "generation", attempt, batch, null);
      List<Long> ids;
      try {
        var output = agents.generate(task);
        QuestionCoveragePlan.validate(output, task);
        ids = bank.storeGenerated(lease, batch, output);
        generatedCount = ids.size();
      } catch (Exception ex) {
        bank.endExecution(
            lease,
            execution,
            false,
            "Generation failed: " + failure(ex) + "; no question batch was accepted");
        continue;
      }
      // Tracking failure after commit must not retry the already persisted generation.
      bank.endExecution(
          lease, execution, true, "Persisted " + ids.size() + " questions as PENDING_REVIEW");
      return Map.of(
          "batchId",
          batch,
          "generated",
          ids.size(),
          "questionIds",
          ids,
          "status",
          "PENDING_REVIEW");
    }
    failures++;
    bank.failBatch(lease, batch);
    return Map.of(
        "batchId", batch, "error", "Generation retry limit reached; no question batch accepted");
  }

  public synchronized Map<String, Object> review_pending() {
    agents.checkTool("review_pending", Map.of());
    if (++toolCalls > properties.maxLlmCalls() * 2)
      return Map.of("error", "Tool call budget exhausted");
    int remaining = properties.validationBatchSize() - reviewCount;
    if (remaining <= 0) return Map.of("error", "Validation budget exhausted for this cycle");
    var ids =
        bank.pendingIds(properties.validationBatchSize()).stream()
            .filter(id -> !visited.contains(id))
            .limit(remaining)
            .toList();
    int reviewed = 0, failed = 0;
    if (reviewRequestSize > 1) return reviewBatches(ids);
    for (Long id : ids) {
      visited.add(id);
      reviewCount++;
      int used = bank.detail(id).question().reviewAttempts();
      boolean success = false;
      for (int attempt = used + 1; attempt <= properties.retryLimit() + 1; attempt++) {
        long execution = bank.beginExecution(lease, "validation", attempt, null, id);
        try {
          Map<String, Object> task = bank.reviewTask(id);
          @SuppressWarnings("unchecked")
          var candidates = (List<Map<String, Object>>) task.get("approvedCandidates");
          Set<Long> allowed = new HashSet<>();
          candidates.forEach(c -> allowed.add(((Number) c.get("id")).longValue()));
          var review = agents.review(task);
          bank.applyReview(lease, id, review, allowed);
        } catch (Exception ex) {
          bank.reviewFailed(lease, id);
          bank.endExecution(
              lease,
              execution,
              false,
              "Review failed: " + failure(ex) + "; no approval was granted by this attempt");
          continue;
        }
        bank.endExecution(
            lease,
            execution,
            true,
            "Question #" + id + " reviewed: " + bank.detail(id).question().status());
        success = true;
        reviewed++;
        break;
      }
      if (!success) {
        failed++;
        failures++;
      }
    }
    return Map.of(
        "reviewed",
        reviewed,
        "failed",
        failed,
        "remainingBudget",
        properties.validationBatchSize() - reviewCount);
  }

  private Map<String, Object> reviewBatches(List<Long> ids) {
    int reviewed = 0, failed = 0;
    for (int offset = 0; offset < ids.size(); offset += reviewRequestSize) {
      var group = ids.subList(offset, Math.min(ids.size(), offset + reviewRequestSize));
      Map<Long, Integer> used = new LinkedHashMap<>();
      for (Long id : group) {
        visited.add(id);
        reviewCount++;
        used.put(id, bank.detail(id).question().reviewAttempts());
      }
      Set<Long> successful = new HashSet<>();
      for (int attempt = 1; attempt <= properties.retryLimit() + 1; attempt++) {
        final int round = attempt;
        var eligible =
            group.stream()
                .filter(
                    id ->
                        !successful.contains(id)
                            && used.get(id) + round <= properties.retryLimit() + 1)
                .toList();
        if (eligible.isEmpty()) break;
        Map<Long, Long> executions = new LinkedHashMap<>();
        eligible.forEach(
            id ->
                executions.put(
                    id, bank.beginExecution(lease, "validation", used.get(id) + round, null, id)));
        Map<Long, Review> decisions = new LinkedHashMap<>();
        try {
          Map<Long, Set<Long>> candidates = new LinkedHashMap<>();
          List<Map<String, Object>> inputs = new ArrayList<>();
          for (Long id : eligible) {
            var task = bank.reviewTask(id);
            @SuppressWarnings("unchecked")
            var matches = (List<Map<String, Object>>) task.get("approvedCandidates");
            Set<Long> allowed = new HashSet<>();
            matches.forEach(c -> allowed.add(((Number) c.get("id")).longValue()));
            candidates.put(id, allowed);
            var q = bank.detail(id).question();
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("questionId", id);
            input.put("text", q.text());
            input.put("topicId", q.topicId());
            input.put("subtopic", q.subtopic());
            input.put("difficulty", q.difficulty());
            input.put("questionType", q.questionType());
            input.put("tags", q.tags());
            input.put("approvedCandidates", matches);
            inputs.add(input);
          }
          var taxonomy =
              bank.taxonomy().stream()
                  .flatMap(
                      c ->
                          c.topics().stream()
                              .map(t -> Map.of("id", t.id(), "name", t.name(), "bank", c.name())))
                  .toList();
          var output =
              agents.reviewBatch(
                  Map.of(
                      "questions",
                      inputs,
                      "taxonomy",
                      taxonomy,
                      "minimumQualityScore",
                      properties.minimumQualityScore(),
                      "minimumRelevanceScore",
                      properties.minimumRelevanceScore()));
          if (output == null || output.reviews() == null)
            throw new IllegalArgumentException("No batch review result");
          for (var item : output.reviews()) {
            if (item == null
                || !eligible.contains(item.questionId())
                || decisions.putIfAbsent(item.questionId(), item.review()) != null)
              throw new IllegalArgumentException(
                  "Batch review must return each requested question exactly once");
          }
          if (!decisions.keySet().equals(new HashSet<>(eligible)))
            throw new IllegalArgumentException("Batch review omitted questions");
          bank.applyReviews(lease, decisions, candidates);
        } catch (Exception ex) {
          for (Long id : eligible) {
            bank.reviewFailed(lease, id);
            bank.endExecution(
                lease,
                executions.get(id),
                false,
                "Batch review failed: "
                    + failure(ex)
                    + "; no approval was granted by this request");
          }
          continue;
        }
        // Committed decisions must never be resubmitted if activity tracking subsequently fails.
        successful.addAll(eligible);
        reviewed += eligible.size();
        for (Long id : eligible)
          bank.endExecution(
              lease,
              executions.get(id),
              true,
              "Question #"
                  + id
                  + " reviewed: "
                  + bank.detail(id).question().status()
                  + " (batch of "
                  + eligible.size()
                  + ")");
      }
      int exhausted = group.size() - successful.size();
      failed += exhausted;
      failures += exhausted;
    }
    return Map.of(
        "reviewed",
        reviewed,
        "failed",
        failed,
        "remainingBudget",
        properties.validationBatchSize() - reviewCount);
  }

  public synchronized String summary() {
    return "Generated "
        + generatedCount
        + " questions; attempted review of "
        + reviewCount
        + " questions; "
        + failures
        + " stage failures";
  }

  public synchronized boolean failed() {
    return failures > 0;
  }

  public synchronized boolean didWork() {
    return generated || reviewCount > 0;
  }

  public synchronized void completeDelegation() {
    if (!generated && assignedTopicId != null) {
      if (reviewCount == 0) review_pending();
      generate_for_topic(assignedTopicId);
    }
    if (reviewCount < properties.validationBatchSize()) review_pending();
  }

  private String failure(Exception ex) {
    return ex instanceof com.interviewbot.backend.exceptions.LLMException
        ? ex.getMessage()
        : ex.getClass().getSimpleName();
  }
}
