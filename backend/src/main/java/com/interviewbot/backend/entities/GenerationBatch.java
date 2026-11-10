package com.interviewbot.backend.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "qb_generation_batch")
public class GenerationBatch extends BankRecord {
  @Column(nullable = false, unique = true, length = 80)
  public String idempotencyKey;

  @ManyToOne(optional = false, fetch = FetchType.EAGER)
  public BankTopic topic;

  @Column(nullable = false)
  public int requestedCount;

  @Column(nullable = false)
  public int generatedCount;

  @Column(nullable = false, length = 32)
  public String status = "RUNNING";

  @Column(nullable = false)
  public long fenceToken;
}
