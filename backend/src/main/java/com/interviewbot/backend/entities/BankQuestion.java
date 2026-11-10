package com.interviewbot.backend.entities;

import com.interviewbot.backend.enums.BankTypes;
import jakarta.persistence.*;

@Entity
@org.hibernate.annotations.Immutable
@org.hibernate.annotations.Subselect(
    "select * from qb_backend_question union all select * from qb_frontend_question union all"
        + " select * from qb_ai_question")
@org.hibernate.annotations.Synchronize({
  "qb_backend_question",
  "qb_frontend_question",
  "qb_ai_question"
})
public class BankQuestion extends BankRecord {
  @Column(nullable = false, columnDefinition = "text")
  public String text;

  @Column(nullable = false, length = 64)
  public String fingerprint;

  @ManyToOne(optional = false, fetch = FetchType.EAGER)
  public BankTopic topic;

  @Column(length = 100)
  public String subtopic;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.Difficulty difficulty;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.QuestionType questionType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.Status status = BankTypes.Status.PENDING_REVIEW;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  public BankTypes.Source source;

  public Integer qualityScore;
  public Integer relevanceScore;

  @Column(nullable = false)
  public int reviewAttempts;

  @Column(columnDefinition = "text")
  public String generationRationale;

  @ManyToOne(fetch = FetchType.EAGER)
  public GenerationBatch batch;

  @ManyToOne(fetch = FetchType.EAGER)
  public BankQuestion duplicateOf;

  @Transient public java.util.Set<BankTag> tags = new java.util.LinkedHashSet<>();
}
