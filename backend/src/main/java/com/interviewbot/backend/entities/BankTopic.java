package com.interviewbot.backend.entities;

import jakarta.persistence.*;

@Entity
@Table(
    name = "qb_topic",
    uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "name"}))
public class BankTopic extends BankRecord {
  @ManyToOne(optional = false, fetch = FetchType.EAGER)
  public BankCategory category;

  @Column(nullable = false, length = 100)
  public String name;

  @Column(nullable = false)
  public boolean enabled = true;
}
