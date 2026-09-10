package com.interviewbot.backend.interview.state;

import com.interviewbot.backend.common.exception.InvalidInterviewStateException;
import com.interviewbot.backend.interview.entity.InterviewStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InterviewStateMachine {
    private final EnumMap<InterviewStatus, Set<InterviewStatus>> allowed = new EnumMap<>(InterviewStatus.class);

    public InterviewStateMachine() {
        allowed.put(InterviewStatus.CREATED, EnumSet.of(InterviewStatus.READY));
        allowed.put(InterviewStatus.READY, EnumSet.of(InterviewStatus.ASKING, InterviewStatus.COMPLETED));
        allowed.put(InterviewStatus.ASKING, EnumSet.of(InterviewStatus.WAITING_FOR_ANSWER));
        allowed.put(InterviewStatus.WAITING_FOR_ANSWER, EnumSet.of(InterviewStatus.EVALUATING, InterviewStatus.COMPLETED));
        allowed.put(InterviewStatus.EVALUATING, EnumSet.of(InterviewStatus.FOLLOW_UP, InterviewStatus.ASKING, InterviewStatus.COMPLETED));
        allowed.put(InterviewStatus.FOLLOW_UP, EnumSet.of(InterviewStatus.ASKING, InterviewStatus.COMPLETED));
        allowed.put(InterviewStatus.COMPLETED, EnumSet.noneOf(InterviewStatus.class));
    }

    public void validate(InterviewStatus from, InterviewStatus to) {
        if (!allowed.getOrDefault(from, Set.of()).contains(to)) {
            throw new InvalidInterviewStateException("Invalid interview state transition: " + from + " -> " + to);
        }
    }

    public InterviewStatus transition(InterviewStatus from, InterviewStatus to) {
        validate(from, to);
        return to;
    }
}
