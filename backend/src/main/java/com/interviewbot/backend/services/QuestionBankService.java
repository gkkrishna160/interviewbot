package com.interviewbot.backend.services;

import static com.interviewbot.backend.dtos.BankDtos.*;
import static com.interviewbot.backend.enums.BankTypes.*;

import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.entities.AgentExecution;
import com.interviewbot.backend.entities.BankCategory;
import com.interviewbot.backend.entities.BankQuestion;
import com.interviewbot.backend.entities.BankTag;
import com.interviewbot.backend.entities.BankTopic;
import com.interviewbot.backend.entities.GenerationBatch;
import com.interviewbot.backend.entities.QuestionTagLink;
import com.interviewbot.backend.entities.QuestionValidation;
import com.interviewbot.backend.repositories.BatchRepository;
import com.interviewbot.backend.repositories.CategoryRepository;
import com.interviewbot.backend.repositories.ExecutionRepository;
import com.interviewbot.backend.repositories.QuestionReadRepository;
import com.interviewbot.backend.repositories.QuestionRepository;
import com.interviewbot.backend.repositories.TagRepository;
import com.interviewbot.backend.repositories.TopicRepository;
import com.interviewbot.backend.repositories.ValidationRepository;
import com.interviewbot.backend.security.DatabaseWriteGuard;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuestionBankService {
  private final DatabaseWriteGuard writeGuard;
  private static final Logger log = LoggerFactory.getLogger(QuestionBankService.class);
  private final CategoryRepository categories;
  private final TopicRepository topics;
  private final TagRepository tags;
  private final QuestionRepository questions;
  private final ValidationRepository validations;
  private final BatchRepository batches;
  private final ExecutionRepository executions;
  private final DuplicateDetector duplicates;
  private final WorkflowLease lease;
  private final BankProperties properties;
  private final BankAgentProperties agentProperties;
  private final Validator validator;

  public QuestionBankService(
      CategoryRepository categories,
      TopicRepository topics,
      TagRepository tags,
      QuestionRepository questions,
      ValidationRepository validations,
      BatchRepository batches,
      ExecutionRepository executions,
      DuplicateDetector duplicates,
      WorkflowLease lease,
      BankAgentProperties agentProperties,
      BankProperties properties,
      Validator validator,
      DatabaseWriteGuard writeGuard) {
    this.writeGuard = writeGuard;
    this.categories = categories;
    this.topics = topics;
    this.tags = tags;
    this.questions = questions;
    this.validations = validations;
    this.batches = batches;
    this.executions = executions;
    this.duplicates = duplicates;
    this.lease = lease;
    this.properties = properties;
    this.agentProperties = agentProperties;
    this.validator = validator;
  }

  public List<TopicView> coverage() {
    var difficultyCounts = questions.approvedDifficultyCounts();
    Map<Long, Map<Status, Long>> grouped = new HashMap<>();
    questions
        .allCounts()
        .forEach(
            c ->
                grouped
                    .computeIfAbsent(c.getTopicId(), id -> new EnumMap<>(Status.class))
                    .put(c.getStatus(), c.getCount()));
    return topics.findByEnabledTrueAndCategoryEnabledTrueOrderByCategoryNameAscNameAsc().stream()
        .map(
            t -> {
              Map<Status, Long> counts = grouped.getOrDefault(t.id, Map.of());
              long approved = counts.getOrDefault(Status.APPROVED, 0L),
                  pending = counts.getOrDefault(Status.PENDING_REVIEW, 0L);
              return new TopicView(
                  t.id,
                  t.category.id,
                  t.category.name,
                  t.name,
                  approved,
                  pending,
                  QuestionCoveragePlan.deficit(
                      properties.minimumQuestionsPerTopic(),
                      difficultyCounts.getOrDefault(t.id, Map.of())));
            })
        .toList();
  }

  public List<CategoryView> taxonomy() {
    List<TopicView> coverage = coverage();
    return categories.findByEnabledTrueOrderByNameAsc().stream()
        .map(
            c ->
                new CategoryView(
                    c.id,
                    c.name,
                    coverage.stream().filter(t -> t.categoryId().equals(c.id)).toList()))
        .toList();
  }

  public List<TopicView> generationGaps() {
    Map<Long, Instant> lastAttempt = new HashMap<>();
    batches.lastAttempts().forEach(a -> lastAttempt.put(a.getTopicId(), a.getLastAttempt()));
    var coverage = coverage();
    Map<Long, Instant> bankLastAttempt = new HashMap<>();
    coverage.forEach(
        t ->
            bankLastAttempt.merge(
                t.categoryId(),
                lastAttempt.getOrDefault(t.id(), Instant.EPOCH),
                (a, b) -> a.isAfter(b) ? a : b));
    return coverage.stream()
        .filter(
            t ->
                t.deficit() > 0
                    && t.pending() < Math.min(t.deficit(), properties.minimumQuestionsPerTopic()))
        .sorted(
            Comparator.comparing((TopicView t) -> bankLastAttempt.get(t.categoryId()))
                .thenComparing(Comparator.comparingInt(TopicView::deficit).reversed())
                .thenComparing(t -> lastAttempt.getOrDefault(t.id(), Instant.EPOCH))
                .thenComparingLong(TopicView::id))
        .toList();
  }

  public Dashboard dashboard() {
    Map<Status, Long> counts = new EnumMap<>(Status.class);
    for (Status status : Status.values()) counts.put(status, questions.countByStatus(status));
    Instant last =
        executions
            .findTopByAgentAndStatusOrderByFinishedAtDesc("orchestrator", RunStatus.SUCCEEDED)
            .map(e -> e.finishedAt)
            .orElse(null);
    return new Dashboard(
        counts,
        questions.countBySource(Source.AI_GENERATED),
        questions.countByStatusAndReviewAttemptsGreaterThanEqual(
            Status.PENDING_REVIEW, properties.retryLimit() + 1),
        coverage(),
        last,
        properties.schedulingEnabled(),
        properties.provider(),
        properties.model());
  }

  public QuestionPage browse(
      Long categoryId,
      Long topicId,
      Difficulty difficulty,
      QuestionType questionType,
      Status status,
      String search,
      String tag,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
    if (search != null && search.length() > 200)
      throw new IllegalArgumentException("Search is too long");
    var result =
        questions.findAll(
            (root, query, cb) -> {
              List<Predicate> p = new ArrayList<>();
              p.add(cb.equal(root.get("status"), status == null ? Status.APPROVED : status));
              if (categoryId != null)
                p.add(cb.equal(root.get("topic").get("category").get("id"), categoryId));
              if (topicId != null) p.add(cb.equal(root.get("topic").get("id"), topicId));
              if (difficulty != null) p.add(cb.equal(root.get("difficulty"), difficulty));
              if (questionType != null) p.add(cb.equal(root.get("questionType"), questionType));
              if (search != null && !search.isBlank())
                p.add(
                    cb.like(
                        cb.lower(root.get("text")),
                        "%" + escapeLike(search.toLowerCase(Locale.ROOT).trim()) + "%",
                        '!'));
              if (tag != null && !tag.isBlank()) {
                var tagged = query.subquery(Long.class);
                var link = tagged.from(QuestionTagLink.class);
                tagged
                    .select(link.get("questionId"))
                    .where(
                        cb.equal(link.get("tag").get("name"), tag.toLowerCase(Locale.ROOT).trim()));
                p.add(root.get("id").in(tagged));
              }
              return cb.and(p.toArray(Predicate[]::new));
            },
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("relevanceScore"), Sort.Order.desc("id"))));
    return new QuestionPage(
        result.getContent().stream().map(this::view).toList(),
        result.getTotalElements(),
        result.getTotalPages(),
        page);
  }

  private String escapeLike(String text) {
    return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  public Detail detail(long id) {
    return new Detail(
        view(question(id)),
        validations.findByQuestionIdOrderByCreatedAtDesc(id).stream()
            .map(
                v ->
                    new ValidationView(
                        v.id,
                        v.previousStatus,
                        v.decision,
                        v.qualityScore,
                        v.relevanceScore,
                        v.reason,
                        v.reviewer,
                        v.model,
                        v.classification,
                        v.duplicateOfId,
                        v.createdAt))
            .toList());
  }

  public List<ExecutionView> activity() {
    return executions.findTop30ByOrderByCreatedAtDesc().stream()
        .map(
            e ->
                new ExecutionView(
                    e.id,
                    e.agent,
                    e.model,
                    e.status,
                    e.attempt,
                    e.batchId,
                    e.questionId,
                    e.createdAt,
                    e.finishedAt,
                    e.error,
                    e.summary))
        .toList();
  }

  @Transactional
  public CategoryView addCategory(NameRequest request) {
    writeGuard.generation();
    validate(request);
    String name = request.name().trim();
    if (name.isBlank()) throw new IllegalArgumentException("Name is required");
    BankCategory c =
        categories
            .findByNameIgnoreCase(name)
            .filter(existing -> existing.enabled)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Use one of the three banks and add a topic within it"));
    return new CategoryView(c.id, c.name, List.of());
  }

  @Transactional
  public TopicView addTopic(long categoryId, NameRequest request) {
    writeGuard.generation();
    validate(request);
    BankCategory c =
        categories
            .findById(categoryId)
            .orElseThrow(() -> new EntityNotFoundException("Category not found"));
    if (!c.enabled) throw new IllegalArgumentException("Choose an active question bank");
    BankTopic t =
        topics
            .findByCategoryIdAndNameIgnoreCase(categoryId, request.name().trim())
            .orElseGet(
                () -> {
                  BankTopic n = new BankTopic();
                  n.category = c;
                  n.name = request.name().trim();
                  return topics.save(n);
                });
    return new TopicView(t.id, c.id, c.name, t.name, 0, 0, properties.minimumQuestionsPerTopic());
  }

  @Transactional
  public QuestionView addManual(Draft draft) {
    writeGuard.generation();
    new com.interviewbot.backend.security.PromptGuard().checkText(draft.text());
    validate(draft);
    return view(questions.save(from(draft, Source.MANUAL, null)));
  }

  @Transactional
  public Detail requeue(long id, RequeueRequest request) {
    writeGuard.generation();
    validate(request);
    BankQuestion q = question(id);
    // Optimistic version prevents overwriting a concurrent model decision.
    audit(
        q,
        Status.PENDING_REVIEW,
        "Manual re-review requested: " + request.reason(),
        "manual",
        "none");
    q.status = Status.PENDING_REVIEW;
    q.reviewAttempts = 0;
    q.duplicateOf = null;
    q.qualityScore = null;
    q.relevanceScore = null;
    questions.saveAndFlush(q);
    return detail(id);
  }

  public List<Long> pendingIds(int count) {
    return questions
        .findByStatusAndReviewAttemptsLessThanOrderByCreatedAtAsc(
            Status.PENDING_REVIEW, properties.retryLimit() + 1, PageRequest.of(0, count))
        .stream()
        .map(q -> q.id)
        .toList();
  }

  public Map<String, Object> generationTask(long topicId, int count) {
    BankTopic topic = topic(topicId);
    var recent =
        questions
            .findAll(
                (root, query, cb) -> cb.equal(root.get("topic").get("id"), topicId),
                PageRequest.of(0, 40, Sort.by("id").descending()))
            .getContent();
    return Map.of(
        "topicId",
        topicId,
        "category",
        topic.category.name,
        "topic",
        topic.name,
        "count",
        count,
        "requestedQuestions",
        QuestionCoveragePlan.create(
            topic.name,
            count,
            questions.availableDifficultyCounts(topicId),
            questions.attemptedDifficultyCounts(topicId)),
        "difficultyCoverage",
        Arrays.stream(Difficulty.values())
            .map(
                d ->
                    Map.of(
                        "difficulty",
                        d,
                        "approved",
                        questions.count(
                            (root, query, cb) ->
                                cb.and(
                                    cb.equal(root.get("topic").get("id"), topicId),
                                    cb.equal(root.get("difficulty"), d),
                                    cb.equal(root.get("status"), Status.APPROVED)))))
            .toList(),
        "existing",
        recent.stream()
            .map(
                q ->
                    Map.of(
                        "text",
                        q.text,
                        "status",
                        q.status,
                        "difficulty",
                        q.difficulty,
                        "type",
                        q.questionType))
            .toList(),
        "typeCoverage",
        Arrays.stream(QuestionType.values())
            .map(
                type ->
                    Map.of(
                        "type",
                        type,
                        "approved",
                        questions.count(
                            (root, query, cb) ->
                                cb.and(
                                    cb.equal(root.get("topic").get("id"), topicId),
                                    cb.equal(root.get("questionType"), type),
                                    cb.equal(root.get("status"), Status.APPROVED)))))
            .toList(),
        "rejectionFeedback",
        recent.stream()
            .filter(q -> q.status == Status.REJECTED)
            .limit(5)
            .flatMap(q -> validations.findByQuestionIdOrderByCreatedAtDesc(q.id).stream().limit(1))
            .map(v -> v.reason)
            .toList());
  }

  public Map<String, Object> reviewTask(long id) {
    BankQuestion q = question(id);
    record Ranked(QuestionReadRepository.Candidate candidate, double score) {}
    PriorityQueue<Ranked> selected = new PriorityQueue<>(Comparator.comparingDouble(Ranked::score));
    // Page projections rather than loading the entire bank into the persistence context.
    for (int page = 0; ; page++) {
      var candidates = questions.approvedCandidates(Status.APPROVED, PageRequest.of(page, 250));
      for (var c : candidates)
        if (!c.getId().equals(id)) {
          double score =
              duplicates.similarity(q.text, c.getText())
                  + (c.getTopicId().equals(q.topic.id) ? 0.08 : 0);
          selected.add(new Ranked(c, score));
          if (selected.size() > properties.similarityCandidates()) selected.poll();
        }
      if (candidates.size() < 250) break;
    }
    List<Map<String, Object>> matches =
        selected.stream()
            .sorted(Comparator.comparingDouble(Ranked::score).reversed())
            .map(
                r ->
                    Map.<String, Object>of(
                        "id",
                        r.candidate().getId(),
                        "text",
                        r.candidate().getText(),
                        "topicId",
                        r.candidate().getTopicId(),
                        "textSimilarity",
                        r.score()))
            .toList();
    return Map.of(
        "question",
        view(q),
        "taxonomy",
        taxonomy(),
        "approvedCandidates",
        matches,
        "minimumQualityScore",
        properties.minimumQualityScore(),
        "minimumRelevanceScore",
        properties.minimumRelevanceScore());
  }

  @Transactional
  public long reserveBatch(WorkflowLease.Lease worker, long topicId, int count) {
    writeGuard.generation();
    lease.assertActive(worker);
    String key = "cycle-" + worker.token() + "-topic-" + topicId;
    return batches
        .findByIdempotencyKey(key)
        .map(b -> b.id)
        .orElseGet(
            () -> {
              BankTopic t = topic(topicId);
              if (coverage().stream().noneMatch(v -> v.id().equals(topicId) && v.deficit() > 0))
                throw new IllegalStateException("Topic coverage already sufficient");
              GenerationBatch b = new GenerationBatch();
              b.idempotencyKey = key;
              b.topic = t;
              b.requestedCount = count;
              b.fenceToken = worker.token();
              return batches.save(b).id;
            });
  }

  @Transactional
  public List<Long> storeGenerated(WorkflowLease.Lease worker, long batchId, Generated output) {
    writeGuard.generation();
    lease.assertActive(worker);
    validate(output);
    GenerationBatch b = batch(batchId);
    if (b.fenceToken != worker.token() || !b.status.equals("RUNNING"))
      throw new IllegalStateException("Batch is no longer writable");
    if (output.questions().size() != b.requestedCount
        || output.questions().stream().anyMatch(d -> !d.topicId().equals(b.topic.id)))
      throw new IllegalArgumentException(
          "Generated batch count or topic differs from delegated task");
    List<Long> ids =
        output.questions().stream()
            .map(d -> questions.save(from(d, Source.AI_GENERATED, b)).id)
            .toList();
    b.generatedCount = ids.size();
    b.status = "PENDING_REVIEW";
    return ids;
  }

  @Transactional
  public void failBatch(WorkflowLease.Lease worker, long id) {
    writeGuard.generation();
    lease.assertActive(worker);
    batch(id).status = "FAILED";
  }

  @Transactional
  public void applyReview(
      WorkflowLease.Lease worker, long id, Review review, Set<Long> candidateIds) {
    writeGuard.generation();
    lease.assertActive(worker);
    validate(review);
    BankQuestion q = question(id);
    if (q.status != Status.PENDING_REVIEW)
      throw new IllegalStateException("Question was already reviewed");
    if (review.decision() == Status.PENDING_REVIEW)
      throw new IllegalArgumentException("Review must decide APPROVED, REJECTED or DUPLICATE");
    BankTopic corrected = topic(review.topicId());
    BankQuestion duplicate = null;
    Status decision = review.decision();
    String reason = review.reason();
    if (decision == Status.DUPLICATE) {
      if (review.duplicateOfId() == null
          || !candidateIds.contains(review.duplicateOfId())
          || id == review.duplicateOfId())
        throw new IllegalArgumentException("Duplicate target must be a supplied candidate");
      duplicate = question(review.duplicateOfId());
      if (duplicate.status != Status.APPROVED)
        throw new IllegalArgumentException("Duplicate target is not approved");
    } else if (review.duplicateOfId() != null)
      throw new IllegalArgumentException("Only duplicate decisions may supply duplicateOfId");
    var exact =
        questions.findByFingerprintAndIdNotAndStatusIn(
            q.fingerprint, q.id, List.of(Status.APPROVED));
    if (!exact.isEmpty()) {
      decision = Status.DUPLICATE;
      duplicate = exact.getFirst();
      reason = "Exact normalized duplicate of #" + duplicate.id + ". " + reason;
    }
    if (decision == Status.APPROVED
        && (review.qualityScore() < properties.minimumQualityScore()
            || review.relevanceScore() < properties.minimumRelevanceScore())) {
      decision = Status.REJECTED;
      reason = "Scores below configured approval thresholds. " + reason;
    }
    QuestionValidation v =
        audit(
            q,
            decision,
            reason,
            "question_validation",
            agentProperties.validation().modelOr(properties.model()));
    v.qualityScore = review.qualityScore();
    v.relevanceScore = review.relevanceScore();
    v.duplicateOfId = duplicate == null ? null : duplicate.id;
    v.classification =
        "topicId="
            + review.topicId()
            + ", difficulty="
            + review.difficulty()
            + ", type="
            + review.questionType()
            + ", tags="
            + review.tags();
    q.topic = corrected;
    q.subtopic = review.subtopic();
    q.difficulty = review.difficulty();
    q.questionType = review.questionType();
    q.tags = resolveTags(review.tags());
    q.status = decision;
    q.qualityScore = review.qualityScore();
    q.relevanceScore = review.relevanceScore();
    q.duplicateOf = duplicate;
    q.reviewAttempts++;
    questions.save(q);
    questions.flush();
    if (q.batch != null
        && questions.count(
                (root, query, cb) ->
                    cb.and(
                        cb.equal(root.get("batch").get("id"), q.batch.id),
                        cb.equal(root.get("status"), Status.PENDING_REVIEW)))
            == 0) q.batch.status = "COMPLETED";
  }

  /**
   * One model request commits all its independent decisions or none. Approve peer targets first.
   */
  @Transactional
  public void applyReviews(
      WorkflowLease.Lease worker, Map<Long, Review> reviews, Map<Long, Set<Long>> candidates) {
    writeGuard.generation();
    lease.assertActive(worker);
    Set<Long> approvedPeers = new HashSet<>();
    reviews.forEach(
        (id, r) -> {
          validate(r);
          if (r.decision() == Status.APPROVED
              && r.qualityScore() >= properties.minimumQualityScore()
              && r.relevanceScore() >= properties.minimumRelevanceScore()) approvedPeers.add(id);
        });
    reviews.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getValue().decision() == Status.DUPLICATE))
        .forEach(
            e -> {
              Set<Long> allowed = new HashSet<>(candidates.getOrDefault(e.getKey(), Set.of()));
              allowed.addAll(approvedPeers);
              allowed.remove(e.getKey());
              applyReview(worker, e.getKey(), e.getValue(), allowed);
            });
  }

  @Transactional
  public void reviewFailed(WorkflowLease.Lease worker, long id) {
    writeGuard.generation();
    lease.assertActive(worker);
    BankQuestion q = question(id);
    if (q.status != Status.PENDING_REVIEW) return;
    q.reviewAttempts++;
    audit(
        q,
        Status.PENDING_REVIEW,
        "Review attempt failed; no approval granted. See agent execution record.",
        "question_validation",
        agentProperties.validation().modelOr(properties.model()));
    questions.save(q);
  }

  @Transactional
  public long beginExecution(
      WorkflowLease.Lease worker, String agent, int attempt, Long batchId, Long questionId) {
    writeGuard.generation();
    lease.assertActive(worker);
    AgentExecution e = new AgentExecution();
    e.agent = agent;
    e.attempt = attempt;
    e.model = agentProperties.modelForAgent(agent, properties.model());
    e.fenceToken = worker.token();
    e.batchId = batchId;
    e.questionId = questionId;
    long id = executions.save(e).id;
    log.info(
        "Question bank agent started: agent={} executionId={} model={} attempt={} batchId={}"
            + " questionId={}",
        agent,
        id,
        e.model,
        attempt,
        batchId,
        questionId);
    return id;
  }

  @Transactional
  public void endExecution(WorkflowLease.Lease worker, long id, boolean succeeded, String summary) {
    writeGuard.generation();
    lease.assertActive(worker);
    AgentExecution e = executions.findById(id).orElseThrow();
    e.status = succeeded ? RunStatus.SUCCEEDED : RunStatus.FAILED;
    e.finishedAt = Instant.now();
    // Persist application-owned summaries only; never exception messages containing URLs, headers
    // or raw model payloads.
    if (succeeded) e.summary = summary.substring(0, Math.min(500, summary.length()));
    else e.error = summary.substring(0, Math.min(500, summary.length()));
    long elapsed = Math.max(0, java.time.Duration.between(e.createdAt, e.finishedAt).toSeconds());
    if (succeeded)
      log.info(
          "Question bank agent finished: agent={} executionId={} status={} elapsedSeconds={}"
              + " summary={}",
          e.agent,
          e.id,
          e.status,
          elapsed,
          e.summary);
    else
      log.warn(
          "Question bank agent finished: agent={} executionId={} status={} elapsedSeconds={}"
              + " error={}",
          e.agent,
          e.id,
          e.status,
          elapsed,
          e.error);
  }

  private BankQuestion from(Draft d, Source source, GenerationBatch batch) {
    validate(d);
    if (d.text().trim().length() < 30)
      throw new IllegalArgumentException("Question text is too short after trimming");
    BankQuestion q = new BankQuestion();
    q.text = d.text().trim();
    q.fingerprint = duplicates.fingerprint(q.text);
    q.topic = topic(d.topicId());
    q.subtopic = d.subtopic();
    q.difficulty = d.difficulty();
    q.questionType = d.questionType();
    q.source = source;
    q.generationRationale = d.rationale();
    q.tags = resolveTags(d.tags());
    q.batch = batch;
    return q;
  }

  private Set<BankTag> resolveTags(List<String> names) {
    Set<BankTag> result = new LinkedHashSet<>();
    for (String raw : names) {
      String name = raw.trim().toLowerCase(Locale.ROOT);
      result.add(
          tags.findByName(name)
              .orElseGet(
                  () -> {
                    BankTag t = new BankTag();
                    t.name = name;
                    return tags.save(t);
                  }));
    }
    return result;
  }

  private QuestionValidation audit(
      BankQuestion q, Status decision, String reason, String reviewer, String model) {
    QuestionValidation v = new QuestionValidation();
    v.question = q;
    v.previousStatus = q.status;
    v.decision = decision;
    v.reason = reason;
    v.reviewer = reviewer;
    v.model = model;
    return validations.save(v);
  }

  private BankTopic topic(long id) {
    BankTopic t =
        topics.findById(id).orElseThrow(() -> new EntityNotFoundException("Topic not found"));
    if (!t.enabled || !t.category.enabled) throw new IllegalArgumentException("Topic is disabled");
    return t;
  }

  private BankQuestion question(long id) {
    return questions
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Question not found"));
  }

  private GenerationBatch batch(long id) {
    return batches.findById(id).orElseThrow(() -> new EntityNotFoundException("Batch not found"));
  }

  private <T> void validate(T value) {
    if (value == null || !validator.validate(value).isEmpty())
      throw new IllegalArgumentException("Invalid question bank data");
  }

  private QuestionView view(BankQuestion q) {
    return new QuestionView(
        q.id,
        q.text,
        q.topic.category.id,
        q.topic.category.name,
        q.topic.id,
        q.topic.name,
        q.subtopic,
        q.difficulty,
        q.questionType,
        q.tags.stream().map(t -> t.name).sorted().toList(),
        q.status,
        q.source,
        q.qualityScore,
        q.relevanceScore,
        q.duplicateOf == null ? null : q.duplicateOf.id,
        q.reviewAttempts,
        q.createdAt,
        q.updatedAt);
  }
}
