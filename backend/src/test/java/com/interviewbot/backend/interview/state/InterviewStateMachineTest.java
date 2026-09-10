package com.interviewbot.backend.interview.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewbot.backend.common.exception.InvalidInterviewStateException;
import com.interviewbot.backend.interview.entity.InterviewStatus;
import org.junit.jupiter.api.Test;

class InterviewStateMachineTest {
    private final InterviewStateMachine stateMachine = new InterviewStateMachine();

    @Test
    void allowsExpectedInterviewProgression() {
        InterviewStatus status = InterviewStatus.CREATED;
        status = stateMachine.transition(status, InterviewStatus.READY);
        status = stateMachine.transition(status, InterviewStatus.ASKING);
        status = stateMachine.transition(status, InterviewStatus.WAITING_FOR_ANSWER);
        status = stateMachine.transition(status, InterviewStatus.EVALUATING);
        status = stateMachine.transition(status, InterviewStatus.ASKING);
        status = stateMachine.transition(status, InterviewStatus.WAITING_FOR_ANSWER);
        status = stateMachine.transition(status, InterviewStatus.COMPLETED);

        assertThat(status).isEqualTo(InterviewStatus.COMPLETED);
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> stateMachine.transition(InterviewStatus.CREATED, InterviewStatus.EVALUATING))
            .isInstanceOf(InvalidInterviewStateException.class)
            .hasMessageContaining("CREATED -> EVALUATING");
    }
}
