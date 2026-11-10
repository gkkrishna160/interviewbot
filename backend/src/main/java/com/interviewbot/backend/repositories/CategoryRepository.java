package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.BankCategory;

public interface CategoryRepository
    extends org.springframework.data.jpa.repository.JpaRepository<BankCategory, Long> {
  java.util.Optional<BankCategory> findByNameIgnoreCase(String name);

  java.util.List<BankCategory> findByEnabledTrueOrderByNameAsc();
}
