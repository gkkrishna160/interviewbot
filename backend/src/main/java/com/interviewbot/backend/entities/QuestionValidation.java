package com.interviewbot.backend.entities;

import com.interviewbot.backend.enums.BankTypes;
import jakarta.persistence.*;

@Entity
@Table(name = "qb_validation")
public class QuestionValidation extends BankRecord {
  @ManyToOne(optional = false, fetch = FetchType.EAGER)
  public BankQuestion question;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.Status previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.Status decision;

  public Integer qualityScore;
  public Integer relevanceScore;

  @Column(nullable = false, columnDefinition = "text")
  public String reason;

  @Column(nullable = false, length = 150)
  public String reviewer;

  @Column(nullable = false, length = 150)
  public String model;

  @Column(columnDefinition = "text")
  public String classification;

  public Long duplicateOfId;
}
