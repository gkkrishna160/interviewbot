package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.GenerationBatch;

public interface BatchRepository
    extends org.springframework.data.jpa.repository.JpaRepository<GenerationBatch, Long> {
  @org.springframework.data.jpa.repository.Query(
      "select b.topic.id as topicId,max(b.createdAt) as lastAttempt from GenerationBatch b group by"
          + " b.topic.id")
  java.util.List<LastAttempt> lastAttempts();

  interface LastAttempt {
    Long getTopicId();

    java.time.Instant getLastAttempt();
  }

  java.util.Optional<GenerationBatch> findByIdempotencyKey(String key);
}
