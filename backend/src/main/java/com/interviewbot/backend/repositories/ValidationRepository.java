package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.QuestionValidation;

public interface ValidationRepository
    extends org.springframework.data.jpa.repository.JpaRepository<QuestionValidation, Long> {
  java.util.List<QuestionValidation> findByQuestionIdOrderByCreatedAtDesc(Long questionId);
}
