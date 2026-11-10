package com.interviewbot.backend.agents;

import static com.interviewbot.backend.dtos.MockDtos.*;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.genai.types.*;
import com.interviewbot.backend.config.AppProperties;
import com.interviewbot.backend.config.InterviewJudgeProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class InterviewAgents {
  private final BaseLlm judge, question;
  private final BankAgentGateway runtime;
  private final AppProperties mode;
  private final int timeout;
  private final int judgeTimeout;

  public InterviewAgents(
      @Lazy @Qualifier("interviewJudgeModel") BaseLlm judge,
      @Lazy @Qualifier("interviewQuestionModel") BaseLlm question,
      BankAgentGateway runtime,
      AppProperties mode,
      @Value("${interview.agent.timeout-seconds:360}") int timeout,
      InterviewJudgeProperties judgeOptions) {
    this.judge = judge;
    this.question = question;
    this.runtime = runtime;
    this.mode = mode;
    this.timeout = timeout;
    this.judgeTimeout = judgeOptions.timeoutSeconds();
  }

  public Judgment judge(Object input) {
    mode.requireInterview();
    return runtime.parse(
        run("interview_judge", "interview-judge", judge, input, judgeTimeout), Judgment.class);
  }

  public SuppliedQuestion question(Object input) {
    mode.requireInterview();
    return runtime.parse(
        run("interview_question", "interview-question", question, input, timeout),
        SuppliedQuestion.class);
  }

  private String run(String name, String resource, BaseLlm model, Object input, int deadline) {
    try {
      String prompt =
          new ClassPathResource("agents/" + resource + ".txt")
              .getContentAsString(StandardCharsets.UTF_8);
      Schema schema =
          Schema.fromJson(
              new ClassPathResource("agents/" + resource + ".schema.json")
                  .getContentAsString(StandardCharsets.UTF_8));
      var agent =
          LlmAgent.builder()
              .name(name)
              .model(model)
              .instruction(prompt)
              .outputSchema(schema)
              .generateContentConfig(
                  GenerateContentConfig.builder()
                      .responseMimeType("application/json")
                      .responseSchema(schema)
                      .build())
              .disallowTransferToParent(true)
              .disallowTransferToPeers(true)
              .build();
      return runtime.run(agent, input, 1, deadline + 15);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Interview agent instructions are unavailable", e);
    }
  }
}
