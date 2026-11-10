package com.interviewbot.backend.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "qb_tag")
public class BankTag extends BankRecord {
  @Column(nullable = false, unique = true, length = 60)
  public String name;
}
