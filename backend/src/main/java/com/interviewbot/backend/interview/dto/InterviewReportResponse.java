package com.interviewbot.backend.interview.dto;

import java.util.List;
import java.util.Map;

public record InterviewReportResponse(
    Long interviewId,
    double overallScore,
    Map<String, Double> topicWisePerformance,
    List<String> strengths,
    List<String> weaknesses,
    List<String> recommendations,
    List<ReportQuestionItem> questions
) {
}
