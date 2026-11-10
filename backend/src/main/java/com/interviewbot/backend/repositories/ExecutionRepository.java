package com.interviewbot.backend.repositories;

import com.interviewbot.backend.entities.AgentExecution;
import com.interviewbot.backend.enums.BankTypes;

public interface ExecutionRepository
    extends org.springframework.data.jpa.repository.JpaRepository<AgentExecution, Long> {
  java.util.List<AgentExecution> findTop30ByOrderByCreatedAtDesc();

  java.util.Optional<AgentExecution> findTopByAgentAndStatusOrderByFinishedAtDesc(
      String agent, BankTypes.RunStatus status);
}
