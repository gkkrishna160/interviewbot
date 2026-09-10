package com.interviewbot.backend.interview.repository;

import com.interviewbot.backend.interview.entity.EvaluationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRepository extends JpaRepository<EvaluationEntity, Long> {
    Optional<EvaluationEntity> findByAnswerId(Long answerId);
}
