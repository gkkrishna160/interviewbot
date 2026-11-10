package com.interviewbot.backend.services;

import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.security.DatabaseWriteGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowLease {
  private final DatabaseWriteGuard writeGuard;

  public record Lease(String owner, long token, Instant deadline) {}

  private final JdbcTemplate jdbc;
  private final BankProperties properties;

  public WorkflowLease(
      JdbcTemplate jdbc, BankProperties properties, DatabaseWriteGuard writeGuard) {
    this.writeGuard = writeGuard;
    this.jdbc = jdbc;
    this.properties = properties;
  }

  private Instant now() {
    return jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
  }

  @Transactional(readOnly = true)
  public String waitingReason() {
    var row =
        jdbc.queryForMap("select expires_at,next_allowed_at from qb_workflow_lock where id=1");
    Instant time = now();
    long leaseSeconds =
        java.time.Duration.between(time, ((Timestamp) row.get("expires_at")).toInstant())
            .getSeconds();
    long intervalSeconds =
        java.time.Duration.between(time, ((Timestamp) row.get("next_allowed_at")).toInstant())
            .getSeconds();
    if (leaseSeconds > 0)
      return "Shared worker lease is active; eligible after lease expiry in about "
          + leaseSeconds
          + " seconds (including a previous process stopped mid-cycle)";
    if (intervalSeconds > 0)
      return "Minimum cycle interval has not elapsed; eligible in about "
          + intervalSeconds
          + " seconds";
    return "Shared lease was claimed by another worker; will check again on the next scheduler"
        + " tick";
  }

  @Transactional
  public Optional<Lease> acquire() {
    writeGuard.generation();
    Instant time = now();
    String owner = UUID.randomUUID().toString();
    Instant deadline = time.plusSeconds(properties.cycleTimeoutSeconds() + 10L);
    int n =
        jdbc.update(
            "update qb_workflow_lock set owner=?,token=token+1,expires_at=?,next_allowed_at=? where"
                + " id=1 and expires_at<=? and next_allowed_at<=?",
            owner,
            Timestamp.from(deadline),
            Timestamp.from(time.plusSeconds(properties.minimumFrequencySeconds())),
            Timestamp.from(time),
            Timestamp.from(time));
    if (n == 0) return Optional.empty();
    long token = jdbc.queryForObject("select token from qb_workflow_lock where id=1", Long.class);
    jdbc.update(
        "update qb_agent_execution set status='TIMED_OUT',finished_at=?,error='Previous worker"
            + " lease expired' where status='RUNNING' and fence_token<?",
        Timestamp.from(time),
        token);
    jdbc.update(
        "update qb_generation_batch set status='FAILED' where status='RUNNING' and fence_token<?",
        token);
    return Optional.of(new Lease(owner, token, deadline));
  }

  /** Held only inside short persistence transactions, never during a model call. */
  @Transactional
  public void assertActive(Lease lease) {
    writeGuard.generation();
    Map<String, Object> row =
        jdbc.queryForMap(
            "select owner,token,expires_at from qb_workflow_lock where id=1 for update");
    if (!Objects.equals(row.get("owner"), lease.owner())
        || ((Number) row.get("token")).longValue() != lease.token()
        || !((Timestamp) row.get("expires_at")).toInstant().isAfter(now())
        || Thread.currentThread().isInterrupted())
      throw new IllegalStateException("Workflow lease expired; stale writes refused");
  }

  @Transactional
  public void release(Lease lease) {
    writeGuard.generation();
    jdbc.update(
        "update qb_workflow_lock set owner=null,expires_at=? where id=1 and owner=? and token=?",
        Timestamp.from(now()),
        lease.owner(),
        lease.token());
  }

  @Transactional
  public void expire(Lease lease) {
    writeGuard.generation();
    jdbc.update(
        "update qb_workflow_lock set owner=null,expires_at=? where id=1 and owner=? and token=?",
        Timestamp.from(now()),
        lease.owner(),
        lease.token());
    jdbc.update(
        "update qb_agent_execution set status='TIMED_OUT',finished_at=?,error='Cycle exceeded"
            + " configured timeout' where status='RUNNING' and fence_token=?",
        Timestamp.from(now()),
        lease.token());
    jdbc.update(
        "update qb_generation_batch set status='FAILED' where status='RUNNING' and fence_token=?",
        lease.token());
  }
}
