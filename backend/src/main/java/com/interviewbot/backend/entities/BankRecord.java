package com.interviewbot.backend.entities;

import jakarta.persistence.*;
import java.time.Instant;

@MappedSuperclass
@EntityListeners(com.interviewbot.backend.security.BankWriteListener.class)
public abstract class BankRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, updatable = false)
  public Instant createdAt;

  @Column(nullable = false)
  public Instant updatedAt;

  @Version public long version;

  @PrePersist
  void create() {
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void update() {
    updatedAt = Instant.now();
  }
}
