package com.interviewbot.backend.interview.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateInterviewRequest(
    @NotBlank String company,
    @NotBlank String role,
    @NotBlank String experienceLevel,
    @NotEmpty List<String> languages,
    List<String> frameworks,
    @NotEmpty List<String> topics,
    @Min(10) int durationMinutes
) {
}
