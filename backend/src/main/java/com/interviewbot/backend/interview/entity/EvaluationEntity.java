package com.interviewbot.backend.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "evaluations")
public class EvaluationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id")
    private CandidateAnswer answer;

    @Column(name = "relevance_score", nullable = false)
    private int relevanceScore;

    @Column(name = "technical_score", nullable = false)
    private int technicalScore;

    @Column(name = "correctness_score", nullable = false)
    private int correctnessScore;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "answered_correctly", nullable = false)
    private boolean answeredCorrectly;

    @Column(name = "needs_retry", nullable = false)
    private boolean needsRetry;

    @Column(name = "follow_up_question", columnDefinition = "TEXT")
    private String followUpQuestion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public CandidateAnswer getAnswer() { return answer; }
    public void setAnswer(CandidateAnswer answer) { this.answer = answer; }
    public int getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(int relevanceScore) { this.relevanceScore = relevanceScore; }
    public int getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(int technicalScore) { this.technicalScore = technicalScore; }
    public int getCorrectnessScore() { return correctnessScore; }
    public void setCorrectnessScore(int correctnessScore) { this.correctnessScore = correctnessScore; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public boolean isAnsweredCorrectly() { return answeredCorrectly; }
    public void setAnsweredCorrectly(boolean answeredCorrectly) { this.answeredCorrectly = answeredCorrectly; }
    public boolean isNeedsRetry() { return needsRetry; }
    public void setNeedsRetry(boolean needsRetry) { this.needsRetry = needsRetry; }
    public String getFollowUpQuestion() { return followUpQuestion; }
    public void setFollowUpQuestion(String followUpQuestion) { this.followUpQuestion = followUpQuestion; }
    public Instant getCreatedAt() { return createdAt; }
}
