package com.interviewbot.backend.security;

import jakarta.persistence.*;
import org.springframework.stereotype.Component;

@Component
public class BankWriteListener {
  private final DatabaseWriteGuard guard;

  public BankWriteListener(DatabaseWriteGuard guard) {
    this.guard = guard;
  }

  @PrePersist
  @PreUpdate
  @PreRemove
  public void check(Object entity) {
    guard.generation();
  }
}
