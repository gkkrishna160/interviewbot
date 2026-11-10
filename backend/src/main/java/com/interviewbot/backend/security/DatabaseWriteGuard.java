package com.interviewbot.backend.security;

import com.interviewbot.backend.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DatabaseWriteGuard {
  private final AppProperties mode;

  public DatabaseWriteGuard(AppProperties mode) {
    this.mode = mode;
  }

  public void generation() {
    mode.requireGeneration();
    transaction();
  }

  private void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Database writes require a writable transaction");
  }

  public void changed(int rows) {
    if (rows != 1)
      throw new org.springframework.dao.OptimisticLockingFailureException(
          "The operation is stale or was already processed; refresh the interview");
  }
}
