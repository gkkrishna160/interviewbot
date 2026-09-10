package com.interviewbot.backend.interview.dto;

import com.interviewbot.backend.interview.entity.InterviewStatus;
import java.time.Instant;
import java.util.List;

public record InterviewResponse(
    Long id,
    String company,
    String role,
    String experienceLevel,
    List<String> languages,
    List<String> frameworks,
    List<String> topics,
    int durationMinutes,
    InterviewStatus status,
    Instant startedAt,
    Instant completedAt,
    Instant createdAt
) {
}
