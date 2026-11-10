package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.BankTopic;

public interface TopicRepository
    extends org.springframework.data.jpa.repository.JpaRepository<BankTopic, Long> {
  java.util.List<BankTopic> findByEnabledTrueAndCategoryEnabledTrueOrderByCategoryNameAscNameAsc();

  java.util.Optional<BankTopic> findByCategoryIdAndNameIgnoreCase(Long categoryId, String name);
}
