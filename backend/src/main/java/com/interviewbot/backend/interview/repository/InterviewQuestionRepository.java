package com.interviewbot.backend.interview.repository;

import com.interviewbot.backend.interview.entity.InterviewQuestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {
    List<InterviewQuestion> findByInterviewIdOrderBySequenceAsc(Long interviewId);

    Optional<InterviewQuestion> findTopByInterviewIdOrderBySequenceDesc(Long interviewId);

    long countByInterviewId(Long interviewId);
}
