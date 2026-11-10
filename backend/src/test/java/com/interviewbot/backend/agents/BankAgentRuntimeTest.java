package com.interviewbot.backend.agents;

import static com.interviewbot.backend.dtos.BankDtos.*;
import static com.interviewbot.backend.enums.BankTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.langchain4j.LangChain4j;
import com.interviewbot.backend.config.BankAgentProperties;
import com.interviewbot.backend.config.BankProperties;
import com.interviewbot.backend.services.DuplicateDetector;
import com.interviewbot.backend.services.QuestionBankService;
import com.interviewbot.backend.services.WorkflowLease;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic model fixture; production agents and ADK routing execute unchanged. */
class BankAgentRuntimeTest {
  @Test
  void modelTimeoutBecomesAnActionableAgentFailure() {
    ChatModel chat = mock(ChatModel.class);
    when(chat.chat(any(ChatRequest.class)))
        .thenThrow(new dev.langchain4j.exception.TimeoutException("request timed out"));
    var base = LangChain4j.builder().chatModel(chat).modelName("test").build();
    var gateway =
        new BankAgentGateway(
            base,
            base,
            base,
            base,
            agentProperties(),
            properties(),
            new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator(),
            new com.interviewbot.backend.config.AppProperties(
                com.interviewbot.backend.config.AppProperties.Mode.GENERATION_MODE));
    assertThatThrownBy(() -> gateway.generate(Map.of("topicId", 1, "count", 1)))
        .hasMessage("Agent question_generation timed out waiting for the model response");
  }

  private BankAgentProperties agentProperties() {
    var options = new BankAgentProperties.ModelOptions(null, true, 0.6, 8192, 10);
    return new BankAgentProperties(options, options);
  }

  private BankProperties properties() {
    return new BankProperties(
        false, 1, 15, 10, 1, 0, 10, 30, 8, 10, 70, 70, "ollama", "test", "http://unused", "");
  }

  @Test
  void realAdkOrchestratorInvokesBothIndependentAgentsThroughBoundedTools() throws Exception {
    ChatModel chat = mock(ChatModel.class);
    AtomicInteger rootCalls = new AtomicInteger();
    AtomicInteger generationCalls = new AtomicInteger();
    AtomicInteger reviewCalls = new AtomicInteger();
    when(chat.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              ChatRequest request = invocation.getArgument(0);
              String instruction = SystemMessage.findFirst(request.messages()).orElseThrow().text();
              AiMessage message;
              if (instruction.contains("independent Question Generation Agent")) {
                generationCalls.incrementAndGet();
                message =
                    AiMessage.from(
                        """
                        {"questions":[{"text":"How does volatile affect visibility while leaving compound read-modify-write operations unsafe?","topicId":1,"subtopic":"Visibility","difficulty":"INTERMEDIATE","questionType":"FUNDAMENTAL","tags":["java"],"rationale":"Tests visibility versus atomicity for mid-level engineers"}]}
                        """);
              } else if (instruction.contains("independent Question Validation and Review Agent")) {
                reviewCalls.incrementAndGet();
                message =
                    AiMessage.from(
                        """
                        {"decision":"APPROVED","qualityScore":90,"relevanceScore":90,"topicId":1,"subtopic":"Visibility","difficulty":"INTERMEDIATE","questionType":"FUNDAMENTAL","tags":["java"],"reason":"Correct distinction with sufficient context","duplicateOfId":null}
                        """);
              } else {
                int call = rootCalls.incrementAndGet();
                message =
                    call == 1
                        ? AiMessage.from(
                            ToolExecutionRequest.builder()
                                .id("generate-1")
                                .name("generate_for_topic")
                                .arguments("{\"topicId\":1}")
                                .build())
                        : call == 2
                            ? AiMessage.from(
                                ToolExecutionRequest.builder()
                                    .id("review-1")
                                    .name("review_pending")
                                    .arguments("{}")
                                    .build())
                            : AiMessage.from("Completed generation and independent review.");
              }
              return ChatResponse.builder().aiMessage(message).build();
            });
    ObjectMapper json = new ObjectMapper();
    var validator = Validation.buildDefaultValidatorFactory().getValidator();
    var baseModel = LangChain4j.builder().chatModel(chat).modelName("test").build();
    BankAgentGateway gateway =
        new BankAgentGateway(
            baseModel,
            baseModel,
            baseModel,
            baseModel,
            agentProperties(),
            properties(),
            json,
            validator,
            new com.interviewbot.backend.config.AppProperties(
                com.interviewbot.backend.config.AppProperties.Mode.GENERATION_MODE));
    QuestionBankService bank = mock(QuestionBankService.class);
    when(bank.coverage()).thenReturn(List.of(new TopicView(1L, 1L, "Java", "Core Java", 0, 0, 15)));
    var lease = new WorkflowLease.Lease("test", 1, Instant.now().plusSeconds(30));
    when(bank.reserveBatch(any(), eq(1L), eq(1))).thenReturn(10L);
    when(bank.generationTask(1, 1)).thenReturn(Map.of("topicId", 1, "count", 1));
    when(bank.storeGenerated(any(), eq(10L), any())).thenReturn(List.of(20L));
    when(bank.pendingIds(1)).thenReturn(List.of(20L));
    when(bank.detail(20))
        .thenReturn(
            new Detail(
                new QuestionView(
                    20L,
                    "text",
                    1L,
                    "Java",
                    1L,
                    "Core Java",
                    null,
                    Difficulty.INTERMEDIATE,
                    QuestionType.FUNDAMENTAL,
                    List.of(),
                    Status.PENDING_REVIEW,
                    Source.AI_GENERATED,
                    null,
                    null,
                    null,
                    0,
                    Instant.now(),
                    Instant.now()),
                List.of()));
    when(bank.reviewTask(20)).thenReturn(Map.of("approvedCandidates", List.of()));
    WorkflowTools tools = new WorkflowTools(bank, gateway, properties(), lease);
    assertThat(gateway.orchestrate(tools, Map.of("gaps", bank.coverage()))).contains("Completed");
    assertThat(generationCalls.get()).isEqualTo(1);
    assertThat(reviewCalls.get()).isEqualTo(1);
    assertThat(rootCalls.get()).isEqualTo(3);
    verify(bank).storeGenerated(eq(lease), eq(10L), any());
    verify(bank).applyReview(eq(lease), eq(20L), any(), eq(Set.of()));
  }

  @Test
  void strictJsonRejectsMissingScoresCoercionDuplicateKeysAndUnknownFields() {
    var gateway =
        new BankAgentGateway(
            mock(com.google.adk.models.BaseLlm.class),
            mock(com.google.adk.models.BaseLlm.class),
            mock(com.google.adk.models.BaseLlm.class),
            mock(com.google.adk.models.BaseLlm.class),
            agentProperties(),
            properties(),
            new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator(),
            new com.interviewbot.backend.config.AppProperties(
                com.interviewbot.backend.config.AppProperties.Mode.GENERATION_MODE));
    String json =
        "{\"questions\":[{\"text\":\"Explain why an unsynchronized compound increment can lose"
            + " updates between Java"
            + " threads.\",\"topicId\":1,\"difficulty\":\"INTERMEDIATE\",\"questionType\":\"FUNDAMENTAL\",\"tags\":[],\"rationale\":\"Concurrency"
            + " semantics\"}]}";
    assertThat(gateway.parse(json, Generated.class).questions()).hasSize(1);
    assertThatThrownBy(
            () ->
                gateway.parse(json.replace("\"topicId\":1", "\"topicId\":\"1\""), Generated.class))
        .hasMessageContaining("invalid structured JSON");
    assertThatThrownBy(
            () ->
                gateway.parse(
                    json.replace("\"topicId\":1", "\"topicId\":1,\"topicId\":2"), Generated.class))
        .hasMessageContaining("invalid structured JSON");
    assertThatThrownBy(
            () ->
                gateway.parse(
                    json.replace("\"topicId\":1", "\"topicId\":1,\"answer\":\"leak\""),
                    Generated.class))
        .hasMessageContaining("invalid structured JSON");
    assertThatThrownBy(() -> gateway.parse("{}", Review.class))
        .hasMessageContaining("required field");
  }

  @Test
  void normalizationDetectsPunctuationCaseAndUnicodeVariants() {
    DuplicateDetector detector = new DuplicateDetector();
    assertThat(detector.fingerprint("How does Java VOLATILE work?"))
        .isEqualTo(detector.fingerprint("Ｈｏｗ does Java volatile work!"));
    assertThat(
            detector.similarity(
                "Java memory visibility between threads",
                "Visibility between Java threads and memory"))
        .isGreaterThan(.8);
  }
}
