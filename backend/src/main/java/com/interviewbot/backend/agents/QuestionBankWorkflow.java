package com.interviewbot.backend.agents;

import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.dtos.BankDtos;
import com.interviewbot.backend.enums.BankTypes;
import com.interviewbot.backend.services.QuestionBankService;
import com.interviewbot.backend.services.WorkflowLease;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.mode",
    havingValue = "generation_mode")
public class QuestionBankWorkflow {
  private static final Logger log = LoggerFactory.getLogger(QuestionBankWorkflow.class);
  private final WorkflowLease leases;
  private final QuestionBankService bank;
  private final BankAgentGateway agents;
  private final BankProperties properties;
  private final int reviewRequestSize;
  private final AtomicBoolean active = new AtomicBoolean();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "question-bank-worker");
            t.setDaemon(true);
            return t;
          });
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "question-bank-timeout");
            t.setDaemon(true);
            return t;
          });

  public QuestionBankWorkflow(
      WorkflowLease leases,
      QuestionBankService bank,
      BankAgentGateway agents,
      BankProperties properties,
      @org.springframework.beans.factory.annotation.Value(
              "${question-bank.validation-request-size:5}")
          int reviewRequestSize) {
    this.leases = leases;
    this.bank = bank;
    this.agents = agents;
    this.properties = properties;
    if (reviewRequestSize < 1 || reviewRequestSize > 10)
      throw new IllegalArgumentException("validation-request-size must be between 1 and 10");
    this.reviewRequestSize = reviewRequestSize;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void logSchedulingConfiguration(ApplicationReadyEvent event) {
    var environment = event.getApplicationContext().getEnvironment();
    log.info(
        "Question bank scheduler enabled={} initialDelayMs={} pollIntervalMs={}"
            + " minimumIntervalSeconds={} generationBatchSize={} validationBatchSize={}",
        properties.schedulingEnabled(),
        environment.getProperty("question-bank.initial-delay-ms", "30000"),
        environment.getProperty("question-bank.schedule-delay-ms", "60000"),
        properties.minimumFrequencySeconds(),
        properties.generationBatchSize(),
        properties.validationBatchSize());
    if (!properties.schedulingEnabled())
      log.info(
          "Question bank automatic cycles are disabled; manual runs remain available in Agent"
              + " activity");
  }

  @Scheduled(
      fixedDelayString = "${question-bank.schedule-delay-ms:60000}",
      initialDelayString = "${question-bank.initial-delay-ms:30000}")
  public void scheduled() {
    if (!properties.schedulingEnabled()) return;
    try {
      if (active.get()) {
        String running =
            bank.activity().stream()
                .filter(e -> e.status() == BankTypes.RunStatus.RUNNING)
                .map(
                    e ->
                        e.agent()
                            + " #"
                            + e.id()
                            + " ("
                            + e.model()
                            + ", "
                            + Math.max(
                                0,
                                java.time.Duration.between(e.createdAt(), java.time.Instant.now())
                                    .toSeconds())
                            + "s elapsed)")
                .collect(java.util.stream.Collectors.joining(", "));
        log.info(
            "Question bank cycle still running; waiting on: {}",
            running.isBlank() ? "worker" : running);
        return;
      }
      Map<String, Object> result = trigger();
      if (!Boolean.TRUE.equals(result.get("accepted")))
        log.info("Question bank scheduler waiting: {}", result.get("reason"));
    } catch (Exception ex) {
      log.error("Question bank scheduler check failed: {}", ex.getClass().getSimpleName());
    }
  }

  public Map<String, Object> trigger() {
    if (!active.compareAndSet(false, true))
      return Map.of("accepted", false, "reason", "A local worker is active");
    Optional<WorkflowLease.Lease> claim;
    try {
      claim = leases.acquire();
    } catch (RuntimeException ex) {
      active.set(false);
      throw ex;
    }
    if (claim.isEmpty()) {
      active.set(false);
      return Map.of("accepted", false, "reason", leases.waitingReason());
    }
    var lease = claim.get();
    long execution;
    try {
      execution = bank.beginExecution(lease, "orchestrator", 1, null, null);
    } catch (RuntimeException ex) {
      leases.release(lease);
      active.set(false);
      throw ex;
    }
    var done = new AtomicBoolean();
    Future<?> job =
        worker.submit(
            () -> {
              try {
                List<BankDtos.TopicView> gaps = bank.generationGaps();
                long pending = bank.pendingIds(properties.validationBatchSize()).size();
                if (gaps.isEmpty() && pending == 0) {
                  bank.endExecution(
                      lease, execution, true, "No coverage gaps or eligible pending questions");
                  return;
                }
                WorkflowTools tools =
                    new WorkflowTools(
                        bank,
                        agents,
                        properties,
                        lease,
                        reviewRequestSize,
                        gaps.isEmpty() ? null : gaps.getFirst().id());
                try {
                  agents.orchestrate(
                      tools,
                      Map.of(
                          "gaps",
                          gaps,
                          "pendingReviewCount",
                          pending,
                          "generationBatchSize",
                          properties.generationBatchSize(),
                          "assignedTopicId",
                          gaps.isEmpty() ? 0L : gaps.getFirst().id(),
                          "validationBudget",
                          properties.validationBatchSize()));
                } catch (RuntimeException coordinationFailure) {
                  leases.assertActive(lease);
                  log.warn(
                      "Coordinator failed; completing bounded delegation for assigned topic {}: {}",
                      gaps.isEmpty() ? null : gaps.getFirst().id(),
                      coordinationFailure.getClass().getSimpleName());
                }
                leases.assertActive(lease);
                tools.completeDelegation();
                if (!tools.didWork())
                  throw new IllegalStateException("Orchestrator did not delegate required work");
                bank.endExecution(lease, execution, !tools.failed(), tools.summary());
                log.info("Question bank cycle token={} {}", lease.token(), tools.summary());
              } catch (Exception ex) {
                log.warn(
                    "Question bank cycle token={} failed: {}",
                    lease.token(),
                    ex.getClass().getSimpleName());
                try {
                  bank.endExecution(
                      lease,
                      execution,
                      false,
                      "Orchestration failed: "
                          + ex.getClass().getSimpleName()
                          + "; inspect provider configuration and stage records");
                } catch (Exception stale) {
                  log.info("Cycle token={} no longer owns its lease", lease.token());
                }
              } finally {
                done.set(true);
                try {
                  leases.release(lease);
                } finally {
                  active.set(false);
                }
              }
            });
    timer.schedule(
        () -> {
          if (!done.get()) {
            leases.expire(lease);
            job.cancel(true);
          }
        },
        properties.cycleTimeoutSeconds(),
        TimeUnit.SECONDS);
    return Map.of("accepted", true, "executionId", execution);
  }

  @PreDestroy
  public void close() {
    worker.shutdownNow();
    timer.shutdownNow();
  }
}
