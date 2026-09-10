package com.interviewbot.backend.interview.repository;

import com.interviewbot.backend.interview.entity.Interview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
}
