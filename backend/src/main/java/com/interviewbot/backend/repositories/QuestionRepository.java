package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.BankQuestion;
import com.interviewbot.backend.entities.BankTag;
import com.interviewbot.backend.enums.Area;
import com.interviewbot.backend.enums.BankTypes;
import com.interviewbot.backend.security.DatabaseWriteGuard;
import jakarta.persistence.EntityManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one physical bank; the immutable JPA union supplies cross-bank queries and associations.
 */
@Repository
@Transactional(readOnly = true)
public class QuestionRepository {
  private final DatabaseWriteGuard writeGuard;
  private final QuestionReadRepository reads;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final EntityManager em;
  private static final String COLUMNS =
      "id,created_at,updated_at,version,text,fingerprint,topic_id,subtopic,difficulty,question_type,status,source,quality_score,relevance_score,review_attempts,generation_rationale,batch_id,duplicate_of_id,bank";

  public QuestionRepository(
      QuestionReadRepository reads,
      JdbcTemplate jdbc,
      EntityManager em,
      DatabaseWriteGuard writeGuard) {
    this.writeGuard = writeGuard;
    this.reads = reads;
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.em = em;
  }

  @Transactional
  public BankQuestion save(BankQuestion q) {
    writeGuard.generation();
    // Flush tags, batches and audit rows before JDBC, in the same Spring-managed transaction.
    em.flush();
    Area target = Objects.requireNonNull(q.topic.category.bank, "Question bank is required");
    Instant now = Instant.now();
    if (q.id == null) {
      GeneratedKeyHolder key = new GeneratedKeyHolder();
      jdbc.update(
          c -> {
            var ps =
                c.prepareStatement(
                    "insert into qb_question_identity(bank) values (?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, target.name());
            return ps;
          },
          key);
      q.id = Objects.requireNonNull(key.getKey()).longValue();
      q.createdAt = now;
      q.updatedAt = now;
      q.version = 0;
      insert(target, q);
    } else {
      Area previous =
          Area.valueOf(
              jdbc.queryForObject(
                  "select bank from qb_question_identity where id=?", String.class, q.id));
      long oldVersion = q.version;
      q.updatedAt = now;
      q.version++;
      var values = values(q, target);
      values.put("oldVersion", oldVersion);
      int changed;
      if (previous == target) {
        String assignments =
            Arrays.stream(COLUMNS.split(","))
                .filter(c -> !c.equals("id") && !c.equals("created_at") && !c.equals("bank"))
                .map(c -> c + "=:" + c)
                .collect(java.util.stream.Collectors.joining(","));
        changed =
            named.update(
                "update "
                    + target.table
                    + " set "
                    + assignments
                    + " where id=:id and version=:oldVersion",
                values);
      } else {
        changed =
            jdbc.update(
                "delete from " + previous.table + " where id=? and version=?", q.id, oldVersion);
        if (changed == 1) {
          jdbc.update("update qb_question_identity set bank=? where id=?", target.name(), q.id);
          insert(target, q);
        }
      }
      if (changed != 1)
        throw new OptimisticLockingFailureException(
            "Question was changed by another writer: " + q.id);
    }
    jdbc.update("delete from qb_question_tag where question_id=?", q.id);
    for (BankTag tag : q.tags)
      jdbc.update("insert into qb_question_tag(question_id,tag_id) values (?,?)", q.id, tag.id);
    return q;
  }

  private void insert(Area bank, BankQuestion q) {
    String placeholders =
        Arrays.stream(COLUMNS.split(","))
            .map(c -> ":" + c)
            .collect(java.util.stream.Collectors.joining(","));
    named.update(
        "insert into " + bank.table + " (" + COLUMNS + ") values (" + placeholders + ")",
        values(q, bank));
  }

  private Map<String, Object> values(BankQuestion q, Area bank) {
    Map<String, Object> v = new HashMap<>();
    v.put("id", q.id);
    v.put("created_at", Timestamp.from(q.createdAt));
    v.put("updated_at", Timestamp.from(q.updatedAt));
    v.put("version", q.version);
    v.put("text", q.text);
    v.put("fingerprint", q.fingerprint);
    v.put("topic_id", q.topic.id);
    v.put("subtopic", q.subtopic);
    v.put("difficulty", q.difficulty.name());
    v.put("question_type", q.questionType.name());
    v.put("status", q.status.name());
    v.put("source", q.source.name());
    v.put("quality_score", q.qualityScore);
    v.put("relevance_score", q.relevanceScore);
    v.put("review_attempts", q.reviewAttempts);
    v.put("generation_rationale", q.generationRationale);
    v.put("batch_id", q.batch == null ? null : q.batch.id);
    v.put("duplicate_of_id", q.duplicateOf == null ? null : q.duplicateOf.id);
    v.put("bank", bank.name());
    return v;
  }

  private void hydrate(Collection<BankQuestion> questions) {
    if (questions.isEmpty()) return;
    Map<Long, BankQuestion> byId = new HashMap<>();
    for (var q : questions) {
      q.tags = new LinkedHashSet<>();
      byId.put(q.id, q);
    }
    named.query(
        "select l.question_id,t.id,t.name from qb_question_tag l join qb_tag t on t.id=l.tag_id"
            + " where l.question_id in (:ids) order by t.name",
        Map.of("ids", byId.keySet()),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              BankTag tag = new BankTag();
              tag.id = rs.getLong("id");
              tag.name = rs.getString("name");
              byId.get(rs.getLong("question_id")).tags.add(tag);
            });
  }

  public Optional<BankQuestion> findById(Long id) {
    var q = reads.findById(id);
    q.ifPresent(v -> hydrate(List.of(v)));
    return q;
  }

  public Page<BankQuestion> findAll(Specification<BankQuestion> spec, Pageable page) {
    var result = reads.findAll(spec, page);
    hydrate(result.getContent());
    return result;
  }

  public long count(Specification<BankQuestion> spec) {
    return reads.count(spec);
  }

  public Map<BankTypes.Difficulty, Long> availableDifficultyCounts(Long topicId) {
    Map<BankTypes.Difficulty, Long> counts = new EnumMap<>(BankTypes.Difficulty.class);
    reads
        .difficultyCounts(
            topicId, List.of(BankTypes.Status.APPROVED, BankTypes.Status.PENDING_REVIEW))
        .forEach(c -> counts.put(c.getDifficulty(), c.getCount()));
    return counts;
  }

  public Map<BankTypes.Difficulty, Long> attemptedDifficultyCounts(Long topicId) {
    Map<BankTypes.Difficulty, Long> counts = new EnumMap<>(BankTypes.Difficulty.class);
    reads
        .difficultyCounts(topicId, List.of(BankTypes.Status.values()))
        .forEach(c -> counts.put(c.getDifficulty(), c.getCount()));
    return counts;
  }

  public Map<Long, Map<BankTypes.Difficulty, Long>> approvedDifficultyCounts() {
    Map<Long, Map<BankTypes.Difficulty, Long>> grouped = new HashMap<>();
    reads
        .approvedDifficultyCounts(BankTypes.Status.APPROVED)
        .forEach(
            c ->
                grouped
                    .computeIfAbsent(
                        c.getTopicId(), id -> new EnumMap<>(BankTypes.Difficulty.class))
                    .put(c.getDifficulty(), c.getCount()));
    return grouped;
  }

  public long countBySource(BankTypes.Source source) {
    return reads.countBySource(source);
  }

  public long countByStatus(BankTypes.Status status) {
    return reads.countByStatus(status);
  }

  public long countByStatusAndReviewAttemptsGreaterThanEqual(
      BankTypes.Status status, int attempts) {
    return reads.countByStatusAndReviewAttemptsGreaterThanEqual(status, attempts);
  }

  public long countByTopicIdAndStatus(Long topicId, BankTypes.Status status) {
    return reads.countByTopicIdAndStatus(topicId, status);
  }

  public List<QuestionReadRepository.TopicStatusCount> allCounts() {
    return reads.allCounts();
  }

  public List<QuestionReadRepository.Candidate> approvedCandidates(
      BankTypes.Status status, Pageable page) {
    return reads.approvedCandidates(status, page);
  }

  public List<BankQuestion> findByStatusAndReviewAttemptsLessThanOrderByCreatedAtAsc(
      BankTypes.Status status, int attempts, Pageable page) {
    return reads.findByStatusAndReviewAttemptsLessThanOrderByCreatedAtAsc(status, attempts, page);
  }

  public List<BankQuestion> findByFingerprintAndIdNotAndStatusIn(
      String fingerprint, Long id, Collection<BankTypes.Status> statuses) {
    return reads.findByFingerprintAndIdNotAndStatusIn(fingerprint, id, statuses);
  }

  @Transactional
  public BankQuestion saveAndFlush(BankQuestion q) {
    writeGuard.generation();
    save(q);
    flush();
    return q;
  }

  @Transactional
  public void flush() {
    writeGuard.generation();
    em.flush();
  }
}
