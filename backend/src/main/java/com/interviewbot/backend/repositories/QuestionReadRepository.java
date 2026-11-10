package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.BankQuestion;
import com.interviewbot.backend.enums.BankTypes;

public interface QuestionReadRepository
    extends org.springframework.data.jpa.repository.JpaRepository<BankQuestion, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<BankQuestion> {
  @org.springframework.data.jpa.repository.Query(
      "select q.difficulty as difficulty,count(q) as count from BankQuestion q where"
          + " q.topic.id=:topicId and q.status in :statuses group by q.difficulty")
  java.util.List<DifficultyCount> difficultyCounts(
      @org.springframework.data.repository.query.Param("topicId") Long topicId,
      @org.springframework.data.repository.query.Param("statuses")
          java.util.Collection<BankTypes.Status> statuses);

  interface DifficultyCount {
    BankTypes.Difficulty getDifficulty();

    long getCount();
  }

  @org.springframework.data.jpa.repository.Query(
      "select q.topic.id as topicId,q.difficulty as difficulty,count(q) as count from BankQuestion"
          + " q where q.status=:status group by q.topic.id,q.difficulty")
  java.util.List<TopicDifficultyCount> approvedDifficultyCounts(
      @org.springframework.data.repository.query.Param("status") BankTypes.Status status);

  interface TopicDifficultyCount extends DifficultyCount {
    Long getTopicId();
  }

  long countBySource(BankTypes.Source source);

  long countByStatusAndReviewAttemptsGreaterThanEqual(BankTypes.Status status, int attempts);

  @org.springframework.data.jpa.repository.Query(
      "select q.id as id,q.text as text,q.topic.id as topicId from BankQuestion q where"
          + " q.status=:status order by q.id")
  java.util.List<Candidate> approvedCandidates(
      @org.springframework.data.repository.query.Param("status") BankTypes.Status status,
      org.springframework.data.domain.Pageable page);

  interface Candidate {
    Long getId();

    String getText();

    Long getTopicId();
  }

  @org.springframework.data.jpa.repository.Query(
      "select q.topic.id as topicId,q.status as status,count(q) as count from BankQuestion q group"
          + " by q.topic.id,q.status")
  java.util.List<TopicStatusCount> allCounts();

  interface TopicStatusCount {
    Long getTopicId();

    BankTypes.Status getStatus();

    long getCount();
  }

  long countByStatus(BankTypes.Status status);

  long countByTopicIdAndStatus(Long topicId, BankTypes.Status status);

  java.util.List<BankQuestion> findByStatusAndReviewAttemptsLessThanOrderByCreatedAtAsc(
      BankTypes.Status status, int attempts, org.springframework.data.domain.Pageable page);

  java.util.List<BankQuestion> findByFingerprintAndIdNotAndStatusIn(
      String fingerprint, Long id, java.util.Collection<BankTypes.Status> statuses);

  java.util.List<BankQuestion> findByStatusOrderByIdAsc(
      BankTypes.Status status, org.springframework.data.domain.Pageable page);

  @org.springframework.data.jpa.repository.Query(
      "select q.status as status, count(q) as count from BankQuestion q where q.topic.id=:topicId"
          + " group by q.status")
  java.util.List<StatusCount> countsForTopic(
      @org.springframework.data.repository.query.Param("topicId") Long topicId);

  interface StatusCount {
    BankTypes.Status getStatus();

    long getCount();
  }
}
