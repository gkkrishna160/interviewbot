package com.interviewbot.backend.services;

import static com.interviewbot.backend.dtos.BankDtos.*;
import static com.interviewbot.backend.enums.BankTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.interviewbot.backend.agents.BankAgentGateway;
import com.interviewbot.backend.agents.QuestionBankWorkflow;
import com.interviewbot.backend.agents.WorkflowTools;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.entities.BankQuestion;
import com.interviewbot.backend.entities.BankTag;
import com.interviewbot.backend.exceptions.LLMException;
import com.interviewbot.backend.repositories.BatchRepository;
import com.interviewbot.backend.repositories.QuestionRepository;
import com.interviewbot.backend.repositories.TopicRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("bank-test")
class QuestionBankIntegrationTest {
  @Autowired QuestionBankService bank;
  @Autowired WorkflowLease leases;
  @Autowired QuestionBankWorkflow workflow;
  @Autowired BankProperties properties;
  @Autowired JdbcTemplate jdbc;
  @Autowired BatchRepository batches;
  @Autowired QuestionRepository questionStore;
  @Autowired TopicRepository topicStore;
  @Autowired MockMvc http;
  @MockBean BankAgentGateway agents;

  @BeforeEach
  void resetDatabase() {
    reset(agents);
    jdbc.update("delete from qb_validation");
    jdbc.update("delete from qb_agent_execution");
    jdbc.update("delete from qb_question_tag");
    for (String table : List.of("qb_backend_question", "qb_frontend_question", "qb_ai_question"))
      jdbc.update("update " + table + " set duplicate_of_id=null");
    for (String table : List.of("qb_backend_question", "qb_frontend_question", "qb_ai_question"))
      jdbc.update("delete from " + table);
    jdbc.update("delete from qb_question_identity");

    jdbc.update("delete from qb_generation_batch");
    jdbc.update("delete from qb_tag");
    jdbc.update(
        "update qb_workflow_lock set owner=null,expires_at='2000-01-01"
            + " 00:00:00',next_allowed_at='2000-01-01 00:00:00'");
  }

  Draft draft(String text) {
    return new Draft(
        text,
        1L,
        "Memory",
        Difficulty.INTERMEDIATE,
        QuestionType.FUNDAMENTAL,
        List.of("java"),
        "Tests memory visibility for experienced Java engineers");
  }

  Review review(Status status, int quality, Long duplicate) {
    return new Review(
        status,
        quality,
        90,
        1L,
        "Concurrency",
        Difficulty.ADVANCED,
        QuestionType.DEBUGGING,
        List.of("threads"),
        "The scenario has enough context and tests happens-before reasoning.",
        duplicate);
  }

  @Test
  void routesThreeBanksAndMovesReviewClassificationWithStableIdentityAndTags() {
    var worker = leases.acquire().orElseThrow();
    long backend =
        bank.addManual(
                draft(
                    "How would you design reliable retries for a tool calling service under partial"
                        + " failure?"))
            .id();
    var frontendDraft =
        new Draft(
            "How do you prevent a stale fetch response from overwriting newer React state?",
            22L,
            "Effects",
            Difficulty.INTERMEDIATE,
            QuestionType.DEBUGGING,
            List.of("react"),
            "Concrete race diagnosis");
    long frontend = bank.addManual(frontendDraft).id();
    long ai =
        bank.addManual(
                new Draft(
                    "How do you measure retrieval grounding quality when deploying an applied AI"
                        + " assistant?",
                    38L,
                    "Evaluation",
                    Difficulty.ADVANCED,
                    QuestionType.SCENARIO,
                    List.of("rag"),
                    "Measurable deployment quality"))
            .id();
    assertThat(Set.of(backend, frontend, ai)).hasSize(3);
    assertThat(jdbc.queryForObject("select count(*) from qb_backend_question", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from qb_frontend_question", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from qb_ai_question", Integer.class))
        .isEqualTo(1);
    var created = bank.detail(backend).question().createdAt();
    bank.applyReview(
        worker,
        backend,
        new Review(
            Status.APPROVED,
            95,
            94,
            37L,
            "Tool retries",
            Difficulty.ADVANCED,
            QuestionType.SCENARIO,
            List.of("idempotency"),
            "Belongs in agentic AI, with concrete failure constraints.",
            null),
        Set.of());
    assertThat(jdbc.queryForObject("select count(*) from qb_backend_question", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from qb_ai_question", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select bank from qb_question_identity where id=?", String.class, backend))
        .isEqualTo("AI");
    var detail = bank.detail(backend);
    assertThat(detail.question().id()).isEqualTo(backend);
    assertThat(detail.question().createdAt()).isEqualTo(created);
    assertThat(detail.question().category()).isEqualTo("AI Engineering");
    assertThat(detail.question().tags()).containsExactly("idempotency");
    assertThat(detail.validations()).hasSize(1);
    assertThat(bank.browse(4L, 37L, null, null, null, null, "idempotency", 0, 20).totalElements())
        .isEqualTo(1);
    bank.requeue(backend, new RequeueRequest("Check current recovery guarantees"));
    assertThat(bank.detail(backend).validations()).hasSize(2);
    assertThat(
            jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema='public' and"
                    + " table_name='qb_question'"))
        .isEmpty();
    leases.release(worker);
  }

  @Test
  void globalDuplicateDetectionAndOptimisticUpdatesSpanPhysicalBanks() {
    var worker = leases.acquire().orElseThrow();
    String text =
        "How should a service handle a retry after the remote operation succeeded but its response"
            + " was lost?";
    long original = bank.addManual(draft(text)).id();
    bank.applyReview(worker, original, review(Status.APPROVED, 95, null), Set.of());
    long copy =
        bank.addManual(
                new Draft(
                    text,
                    37L,
                    "Retries",
                    Difficulty.ADVANCED,
                    QuestionType.SCENARIO,
                    List.of("tools"),
                    "Retry reasoning"))
            .id();
    bank.applyReview(
        worker,
        copy,
        new Review(
            Status.APPROVED,
            95,
            94,
            37L,
            "Retries",
            Difficulty.ADVANCED,
            QuestionType.SCENARIO,
            List.of("tools"),
            "Review retry behavior",
            null),
        Set.of());
    assertThat(bank.detail(copy).question().duplicateOfId()).isEqualTo(original);
    assertThat(bank.detail(copy).question().status()).isEqualTo(Status.DUPLICATE);
    BankQuestion first = questionStore.findById(original).orElseThrow();
    BankQuestion stale = questionStore.findById(original).orElseThrow();
    first.subtopic = "Updated";
    questionStore.save(first);
    stale.subtopic = "Stale";
    assertThatThrownBy(() -> questionStore.save(stale))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    assertThat(bank.detail(original).question().subtopic()).isEqualTo("Updated");
    leases.release(worker);
  }

  @Test
  void failedBankMoveRollsBackPayloadIdentityAndTagsTogether() {
    long id =
        bank.addManual(
                draft(
                    "How do you handle a failed tool invocation without repeating an irreversible"
                        + " operation?"))
            .id();
    BankQuestion q = questionStore.findById(id).orElseThrow();
    q.topic = topicStore.findById(37L).orElseThrow();
    BankTag missing = new BankTag();
    missing.id = Long.MAX_VALUE;
    q.tags = Set.of(missing);
    assertThatThrownBy(() -> questionStore.save(q))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "select bank from qb_question_identity where id=?", String.class, id))
        .isEqualTo("BACKEND");
    assertThat(jdbc.queryForObject("select count(*) from qb_ai_question", Integer.class)).isZero();
    assertThat(bank.detail(id).question().tags()).containsExactly("java");
  }

  @Test
  void migrationSeedsTaxonomyOnlyAndSupportsExtensionWithoutSchemaChanges() {
    assertThat(bank.taxonomy()).hasSize(3);
    assertThat(bank.coverage()).hasSize(38);
    assertThat(bank.taxonomy())
        .anySatisfy(
            c -> {
              assertThat(c.name()).isEqualTo("AI Engineering");
              assertThat(c.topics())
                  .anySatisfy(t -> assertThat(t.name()).isEqualTo("Agentic AI Engineering"));
            });
    assertThat(bank.dashboard().generated()).isZero();
    assertThatThrownBy(() -> bank.addCategory(new NameRequest("Rust")))
        .isInstanceOf(IllegalArgumentException.class);
    var topic = bank.addTopic(2L, new NameRequest("Rust Ownership"));
    assertThat(topic.category()).isEqualTo("Backend / Java / Spring Boot");
    jdbc.update("delete from qb_topic where id=?", topic.id());
  }

  @Test
  void generatedQuestionsArePendingUntilIndependentReviewAndReclassificationIsAudited() {
    var lease = leases.acquire().orElseThrow();
    long batch = bank.reserveBatch(lease, 1, 2);
    var ids =
        bank.storeGenerated(
            lease,
            batch,
            new Generated(
                List.of(
                    draft(
                        "Why can a Java thread observe an outdated value without a happens-before"
                            + " relationship?"),
                    draft(
                        "How does safe publication differ from making all fields volatile in a Java"
                            + " service?"))));
    assertThat(bank.browse(null, null, null, null, null, null, null, 0, 20).totalElements())
        .isZero();
    bank.applyReview(lease, ids.getFirst(), review(Status.APPROVED, 90, null), Set.of());
    var detail = bank.detail(ids.getFirst());
    assertThat(detail.question().status()).isEqualTo(Status.APPROVED);
    assertThat(detail.question().difficulty()).isEqualTo(Difficulty.ADVANCED);
    assertThat(detail.question().tags()).containsExactly("threads");
    assertThat(detail.validations())
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.previousStatus()).isEqualTo(Status.PENDING_REVIEW);
              assertThat(v.reviewer()).isEqualTo("question_validation");
              assertThat(v.model()).isEqualTo("reviewer-test");
            });
    assertThat(
            bank.browse(
                    2L,
                    1L,
                    Difficulty.ADVANCED,
                    QuestionType.DEBUGGING,
                    null,
                    "outdated",
                    null,
                    0,
                    20)
                .totalElements())
        .isEqualTo(1);
    assertThat(batches.findById(batch).orElseThrow().status).isEqualTo("PENDING_REVIEW");
    leases.release(lease);
  }

  @Test
  void exactDuplicatesOverrideAnIncorrectModelApprovalWithoutDeletingRows() {
    var lease = leases.acquire().orElseThrow();
    String text =
        "Explain how Java volatile affects visibility and why compound increments remain unsafe.";
    long first = bank.addManual(draft(text)).id();
    bank.applyReview(lease, first, review(Status.APPROVED, 90, null), Set.of());
    long second = bank.addManual(draft(text.toUpperCase() + "!")).id();
    bank.applyReview(lease, second, review(Status.APPROVED, 90, null), Set.of());
    assertThat(bank.detail(second).question().status()).isEqualTo(Status.DUPLICATE);
    assertThat(bank.detail(second).question().duplicateOfId()).isEqualTo(first);
    assertThat(jdbc.queryForObject("select count(*) from qb_backend_question", Integer.class))
        .isEqualTo(2);
    leases.release(lease);
  }

  @Test
  void semanticDuplicateMustReferenceAnApprovedCandidateAndDecisionsCanBeReversed() {
    var lease = leases.acquire().orElseThrow();
    long first =
        bank.addManual(
                draft(
                    "When does a Java synchronized block establish a happens-before relation"
                        + " between threads?"))
            .id();
    bank.applyReview(lease, first, review(Status.APPROVED, 90, null), Set.of());
    long second =
        bank.addManual(
                draft(
                    "How can acquiring and releasing the same monitor safely publish shared state"
                        + " to another thread?"))
            .id();
    assertThat((List<?>) bank.reviewTask(second).get("approvedCandidates")).hasSize(1);
    assertThatThrownBy(
            () -> bank.applyReview(lease, second, review(Status.DUPLICATE, 90, first), Set.of()))
        .hasMessageContaining("supplied candidate");
    bank.applyReview(lease, second, review(Status.DUPLICATE, 90, first), Set.of(first));
    assertThat(bank.detail(second).question().status()).isEqualTo(Status.DUPLICATE);
    var requeued = bank.requeue(second, new RequeueRequest("Probe a distinct monitor aspect"));
    assertThat(requeued.question().status()).isEqualTo(Status.PENDING_REVIEW);
    assertThat(requeued.validations()).hasSize(2);
    leases.release(lease);
  }

  @Test
  void scoreThresholdsAndInvalidGenerationCannotBypassTheReviewGate() {
    var lease = leases.acquire().orElseThrow();
    long id =
        bank.addManual(
                draft(
                    "Why is a concurrent read unsafe when a HashMap is mutated without"
                        + " synchronization?"))
            .id();
    bank.applyReview(lease, id, review(Status.APPROVED, 20, null), Set.of());
    assertThat(bank.detail(id).question().status()).isEqualTo(Status.REJECTED);
    long batch = bank.reserveBatch(lease, 1, 2);
    assertThatThrownBy(
            () ->
                bank.storeGenerated(
                    lease,
                    batch,
                    new Generated(
                        List.of(
                            draft(
                                "Explain safe construction and escape of the this reference in"
                                    + " concurrent Java applications.")))))
        .hasMessageContaining("batch count");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from qb_backend_question where batch_id=?", Integer.class, batch))
        .isZero();
    leases.release(lease);
  }

  @Test
  void staleWorkerCannotWriteAndFrequencyLimitSurvivesRelease() {
    var lease = leases.acquire().orElseThrow();
    long batch = bank.reserveBatch(lease, 1, 2);
    leases.expire(lease);
    assertThatThrownBy(
            () ->
                bank.storeGenerated(
                    lease,
                    batch,
                    new Generated(
                        List.of(
                            draft(
                                "Explain how final fields participate in safe publication across"
                                    + " Java threads."),
                            draft(
                                "How would you diagnose a stale-read bug without a proper"
                                    + " happens-before edge?")))))
        .hasMessageContaining("stale writes");
    assertThat(leases.acquire()).isEmpty();
    assertThat(leases.waitingReason()).contains("Minimum cycle interval");
  }

  @Test
  void onlyOneConcurrentWorkerCanClaimTheSharedLease() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Boolean> acquire =
          () -> {
            start.await();
            return leases.acquire().isPresent();
          };
      Future<Boolean> a = pool.submit(acquire), b = pool.submit(acquire);
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void generationRetriesReuseTheReservedBatchAndReviewFailuresStayPending() {
    var lease = leases.acquire().orElseThrow();
    when(agents.generate(any()))
        .thenThrow(new LLMException("bad JSON"))
        .thenReturn(
            new Generated(
                List.of(
                    draft(
                        "How can Java volatile visibility fail to make a compound check-then-act"
                            + " sequence atomic?"),
                    draft(
                        "Why can an object escape during construction even when its reference is"
                            + " stored in a thread-safe collection?"))));
    when(agents.review(any())).thenThrow(new LLMException("unavailable"));
    WorkflowTools tools = new WorkflowTools(bank, agents, properties, lease);
    assertThat(tools.generate_for_topic(1).get("generated")).isEqualTo(2);
    assertThat(tools.generate_for_topic(1)).containsKey("error");
    tools.review_pending();
    assertThat(batches.count()).isEqualTo(1);
    verify(agents, times(2)).generate(any());
    verify(agents, times(4)).review(any());
    assertThat(bank.dashboard().counts().get(Status.PENDING_REVIEW)).isEqualTo(2);
    assertThat(bank.dashboard().reviewFailures()).isEqualTo(2);
    assertThat(bank.activity())
        .filteredOn(e -> e.agent().equals("generation"))
        .allSatisfy(e -> assertThat(e.model()).isEqualTo("writer-test"));
    assertThat(bank.activity())
        .filteredOn(e -> e.agent().equals("validation"))
        .allSatisfy(e -> assertThat(e.model()).isEqualTo("reviewer-test"));
    leases.release(lease);
  }

  @Test
  void executionTimeoutCancelsAndFencesTheWorker() {
    when(agents.orchestrate(any(), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(10000);
              return "late";
            });
    assertThat(workflow.trigger().get("accepted")).isEqualTo(true);
    await()
        .atMost(java.time.Duration.ofSeconds(7))
        .untilAsserted(
            () ->
                assertThat(bank.activity())
                    .anySatisfy(e -> assertThat(e.status()).isEqualTo(RunStatus.TIMED_OUT)));
    await()
        .atMost(java.time.Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select owner from qb_workflow_lock where id=1", String.class))
                    .isNull());
  }

  @Test
  void browsingApiKeepsPendingQuestionsOutOfTheDefaultBankAndReturnsTypedDetails()
      throws Exception {
    long id =
        bank.addManual(
                draft(
                    "How does Java safely publish an immutable object to readers on different"
                        + " threads?"))
            .id();
    http.perform(get("/api/question-bank/questions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    http.perform(get("/api/question-bank/questions").param("status", "PENDING_REVIEW"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(id))
        .andExpect(jsonPath("$.content[0].category").value("Backend / Java / Spring Boot"));
    http.perform(get("/api/question-bank/questions/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.validations").isArray());
    http.perform(get("/api/question-bank/questions").param("size", "101"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/question-bank/questions/999999")).andExpect(status().isNotFound());
  }

  @Test
  void failedGenerationAttemptsRotateBehindEquallyUndercoveredTopics() {
    var lease = leases.acquire().orElseThrow();
    long id = bank.reserveBatch(lease, 1, 2);
    bank.failBatch(lease, id);
    assertThat(bank.generationGaps().getFirst().id()).isNotEqualTo(1L);
    leases.release(lease);
  }

  @Test
  void rotatesAcrossAllThreeBanksBeforeReturningToBackend() {
    assertThat(bank.generationGaps().getFirst().categoryId()).isEqualTo(2L);
    var lease = leases.acquire().orElseThrow();
    bank.failBatch(lease, bank.reserveBatch(lease, 1, 2));
    assertThat(bank.generationGaps().getFirst().categoryId()).isEqualTo(3L);
    bank.failBatch(lease, bank.reserveBatch(lease, 21, 2));
    assertThat(bank.generationGaps().getFirst().categoryId()).isEqualTo(4L);
    leases.release(lease);
  }

  @Test
  void coordinatorFailureStillDelegatesToIndependentSpecialistsWithinBudgets() {
    when(agents.orchestrate(any(), any()))
        .thenThrow(new LLMException("Coordinator tool routing failed"));
    when(agents.generate(any()))
        .thenReturn(
            new Generated(
                List.of(
                    draft("Why is String immutable in Java, and what benefits does this provide?"),
                    draft(
                        "How does Java pass-by-value behave when a method receives an object"
                            + " reference?"))));
    when(agents.review(any())).thenReturn(review(Status.APPROVED, 95, null));
    assertThat(workflow.trigger()).containsEntry("accepted", true);
    await()
        .atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(
            () -> assertThat(bank.dashboard().counts().get(Status.APPROVED)).isEqualTo(2));
    await()
        .atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select owner from qb_workflow_lock where id=1", String.class))
                    .isNull());
    verify(agents, times(1)).generate(any());
    verify(agents, times(2)).review(any());
    assertThat(bank.activity())
        .filteredOn(e -> e.agent().equals("orchestrator"))
        .allSatisfy(e -> assertThat(e.status()).isEqualTo(RunStatus.SUCCEEDED));
  }

  @Test
  void tenQuestionsAreGeneratedForTheAssignedTopicAndReviewedInTwoRequests() {
    var lease = leases.acquire().orElseThrow();
    var settings =
        new BankProperties(
            false,
            10,
            15,
            10,
            10,
            1,
            10,
            120,
            8,
            10,
            70,
            70,
            "ollama",
            "test",
            "http://unused",
            "");
    when(agents.generate(any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> task = invocation.getArgument(0);
              @SuppressWarnings("unchecked")
              var slots = (List<QuestionCoveragePlan.Slot>) task.get("requestedQuestions");
              return new Generated(
                  slots.stream()
                      .map(
                          s ->
                              new Draft(
                                  "Explain "
                                      + s.concept()
                                      + " in Java, with a clear example for this concept.",
                                  1L,
                                  s.concept(),
                                  s.difficulty(),
                                  s.questionType(),
                                  List.of("java"),
                                  "Tests the requested concept at its declared level"))
                      .toList());
            });
    when(agents.reviewBatch(any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> task = invocation.getArgument(0);
              @SuppressWarnings("unchecked")
              var inputs = (List<Map<String, Object>>) task.get("questions");
              return new BatchReview(
                  inputs.stream()
                      .map(
                          q ->
                              new ReviewedQuestion(
                                  (Long) q.get("questionId"),
                                  new Review(
                                      Status.APPROVED,
                                      95,
                                      94,
                                      (Long) q.get("topicId"),
                                      (String) q.get("subtopic"),
                                      (Difficulty) q.get("difficulty"),
                                      (QuestionType) q.get("questionType"),
                                      List.of("java"),
                                      "Clear and correct for this level",
                                      null)))
                      .toList());
            });
    WorkflowTools tools = new WorkflowTools(bank, agents, settings, lease, 5, 1L);
    assertThat(tools.generate_for_topic(22)).containsEntry("assignedTopicId", 1L);
    verify(agents, never()).generate(any());
    assertThat(tools.generate_for_topic(1)).containsEntry("generated", 10);
    assertThat(bank.dashboard().counts().get(Status.PENDING_REVIEW)).isEqualTo(10);
    assertThat(tools.review_pending()).containsEntry("reviewed", 10);
    verify(agents, times(1)).generate(any());
    verify(agents, times(2)).reviewBatch(any());
    verify(agents, never()).review(any());
    assertThat(bank.dashboard().counts().get(Status.APPROVED)).isEqualTo(10);
    for (var level : Difficulty.values())
      assertThat(bank.browse(2L, 1L, level, null, null, null, null, 0, 20).totalElements())
          .isGreaterThan(0);
    assertThat(bank.generationGaps().getFirst().id()).isNotEqualTo(1L);
    leases.release(lease);
  }

  @Test
  void batchReviewRejectsMissingAndRepeatedIdsWithoutApprovingAnyQuestion() {
    var lease = leases.acquire().orElseThrow();
    long first =
        bank.addManual(
                draft("Why is String immutable in Java, and what benefits does that provide?"))
            .id();
    bank.addManual(draft("How can a Java application detect and recover from a two-lock deadlock?"))
        .id();
    when(agents.reviewBatch(any()))
        .thenReturn(
            new BatchReview(
                List.of(new ReviewedQuestion(first, review(Status.APPROVED, 90, null)))))
        .thenReturn(
            new BatchReview(
                List.of(
                    new ReviewedQuestion(first, review(Status.APPROVED, 90, null)),
                    new ReviewedQuestion(first, review(Status.APPROVED, 90, null)))));
    var tools = new WorkflowTools(bank, agents, properties, lease, 5, null);
    assertThat(tools.review_pending()).containsEntry("failed", 2);
    assertThat(bank.dashboard().counts().get(Status.APPROVED)).isZero();
    assertThat(bank.dashboard().counts().get(Status.PENDING_REVIEW)).isEqualTo(2);
    assertThat(bank.detail(first).question().reviewAttempts()).isEqualTo(2);
    leases.release(lease);
  }

  @Test
  void batchReviewsSupportPeerDuplicatesAndRollBackAllDecisionsOnInvalidInput() {
    var lease = leases.acquire().orElseThrow();
    long first =
        bank.addManual(
                draft("Why is String immutable in Java, and how does this affect safe sharing?"))
            .id();
    long second =
        bank.addManual(
                draft(
                    "What benefits come from Java String immutability when sharing values across"
                        + " callers?"))
            .id();
    var bad =
        new Review(
            Status.APPROVED,
            90,
            90,
            999999L,
            "Strings",
            Difficulty.BEGINNER,
            QuestionType.FUNDAMENTAL,
            List.of("java"),
            "Invalid classification",
            null);
    Map<Long, Review> invalid = new LinkedHashMap<>();
    invalid.put(first, review(Status.APPROVED, 90, null));
    invalid.put(second, bad);
    assertThatThrownBy(() -> bank.applyReviews(lease, invalid, Map.of()))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    assertThat(bank.detail(first).question().status()).isEqualTo(Status.PENDING_REVIEW);
    assertThat(bank.detail(first).validations()).isEmpty();
    Map<Long, Review> valid = new LinkedHashMap<>();
    valid.put(second, review(Status.DUPLICATE, 90, first));
    valid.put(first, review(Status.APPROVED, 90, null));
    bank.applyReviews(lease, valid, Map.of());
    assertThat(bank.detail(first).question().status()).isEqualTo(Status.APPROVED);
    assertThat(bank.detail(second).question().duplicateOfId()).isEqualTo(first);
    leases.release(lease);
  }

  @Test
  void generationModeDoesNotExposeCandidateAssessment() throws Exception {
    http.perform(
            post("/api/interview/questions")
                .contentType("application/json")
                .content("{\"areas\":[\"BACKEND\"]}"))
        .andExpect(status().isNotFound());
    http.perform(get("/api/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("generation_mode"));
  }
}
