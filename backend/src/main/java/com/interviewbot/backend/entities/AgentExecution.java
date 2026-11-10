package com.interviewbot.backend.entities;

import com.interviewbot.backend.enums.BankTypes;
import jakarta.persistence.*;

@Entity
@Table(name = "qb_agent_execution")
public class AgentExecution extends BankRecord {
  @Column(nullable = false, length = 40)
  public String agent;

  @Column(nullable = false, length = 150)
  public String model;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.RunStatus status = BankTypes.RunStatus.RUNNING;

  @Column(nullable = false)
  public int attempt;

  @Column(nullable = false)
  public long fenceToken;

  public Long batchId;
  public Long questionId;
  public java.time.Instant finishedAt;

  @Column(length = 500)
  public String error;

  @Column(length = 500)
  public String summary;
}
