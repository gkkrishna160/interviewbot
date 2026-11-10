package com.interviewbot.backend.entities;

import com.interviewbot.backend.enums.Area;
import jakarta.persistence.*;

@Entity
@Table(name = "qb_category")
public class BankCategory extends BankRecord {
  @Column(nullable = false, unique = true, length = 100)
  public String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public Area bank;

  @Column(nullable = false)
  public boolean enabled = true;
}
