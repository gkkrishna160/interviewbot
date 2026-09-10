package com.interviewbot.backend.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "candidate_answers")
public class CandidateAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id")
    private InterviewQuestion question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "answered_at", nullable = false, updatable = false)
    private Instant answeredAt;

    @OneToOne(mappedBy = "answer")
    private EvaluationEntity evaluation;

    @PrePersist
    void onCreate() {
        answeredAt = Instant.now();
    }

    public Long getId() { return id; }
    public InterviewQuestion getQuestion() { return question; }
    public void setQuestion(InterviewQuestion question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Instant getAnsweredAt() { return answeredAt; }
    public EvaluationEntity getEvaluation() { return evaluation; }
}
