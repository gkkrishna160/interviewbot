package com.interviewbot.backend.interview.repository;

import com.interviewbot.backend.interview.entity.CandidateAnswer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateAnswerRepository extends JpaRepository<CandidateAnswer, Long> {
    Optional<CandidateAnswer> findTopByQuestionIdOrderByAnsweredAtDesc(Long questionId);

    List<CandidateAnswer> findByQuestionInterviewIdOrderByAnsweredAtAsc(Long interviewId);
}
