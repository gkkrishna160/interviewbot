package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.BankTag;

public interface TagRepository
    extends org.springframework.data.jpa.repository.JpaRepository<BankTag, Long> {
  java.util.Optional<BankTag> findByName(String name);
}
