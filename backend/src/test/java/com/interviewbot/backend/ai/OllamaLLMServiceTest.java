package com.interviewbot.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewbot.backend.ai.dto.Difficulty;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewContext;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.dto.QuestionType;
import com.interviewbot.backend.ai.prompt.EvaluationPromptBuilder;
import com.interviewbot.backend.ai.prompt.InterviewPlanPromptBuilder;
import com.interviewbot.backend.ai.prompt.QuestionPromptBuilder;
import com.interviewbot.backend.common.exception.LLMException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaLLMServiceTest {
    private MockRestServiceServer server;
    private OllamaLLMService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MappingJackson2HttpMessageConverter ollamaJsonConverter = new MappingJackson2HttpMessageConverter(objectMapper);
        ollamaJsonConverter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM));
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://ollama.test")
            .messageConverters(converters -> {
                converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                converters.add(ollamaJsonConverter);
            });
        server = MockRestServiceServer.bindTo(builder).build();
        service = new OllamaLLMService(
            builder.build(),
            new OllamaProperties("http://ollama.test", "llama3.2:3b", 5, 30),
            new QuestionPromptBuilder(),
            new EvaluationPromptBuilder(),
            new InterviewPlanPromptBuilder(),
            new StructuredResponseParser(objectMapper)
        );
    }

    @Test
    void generateQuestionParsesSuccessfulOllamaJsonResponse() {
        expectOllama("""
            {
              "model": "llama3.2:3b",
              "response": "{\\"question\\":\\"Explain hashCode and equals in Java.\\",\\"topic\\":\\"Core Java\\",\\"difficulty\\":\\"MEDIUM\\",\\"questionType\\":\\"PRIMARY\\",\\"expectedConcepts\\":[\\"equals contract\\",\\"hashCode contract\\"]}",
              "done": true,
              "total_duration": 6700000000
            }
            """, MediaType.APPLICATION_JSON);

        Question question = service.generateQuestion(context());

        assertThat(question.question()).contains("hashCode");
        assertThat(question.topic()).isEqualTo("Core Java");
        assertThat(question.difficulty()).isEqualTo(Difficulty.MEDIUM);
        server.verify();
    }

    @Test
    void generateQuestionHandlesOctetStreamOllamaResponse() {
        expectOllama("""
            {
              "response": "{\\"question\\":\\"What is dependency injection in Spring?\\",\\"topic\\":\\"Spring Boot\\",\\"difficulty\\":\\"EASY\\",\\"questionType\\":\\"PRIMARY\\",\\"expectedConcepts\\":[\\"IoC\\",\\"beans\\"]}",
              "done": true
            }
            """, MediaType.APPLICATION_OCTET_STREAM);

        Question question = service.generateQuestion(context());

        assertThat(question.topic()).isEqualTo("Spring Boot");
        assertThat(question.expectedConcepts()).contains("IoC", "beans");
        server.verify();
    }

    @Test
    void evaluateAnswerParsesStructuredEvaluation() {
        expectOllama("""
            {
              "response": "{\\"relevanceScore\\":9,\\"technicalScore\\":8,\\"correctnessScore\\":8,\\"feedback\\":\\"Relevant and mostly correct.\\",\\"answeredCorrectly\\":true,\\"needsRetry\\":false,\\"followUpQuestion\\":null}",
              "done": true
            }
            """, MediaType.APPLICATION_JSON);

        Evaluation evaluation = service.evaluateAnswer(
            new Question("Explain ThreadPoolExecutor.", "Java Concurrency", Difficulty.MEDIUM, QuestionType.PRIMARY, List.of("worker threads")),
            "It manages worker threads and a task queue."
        );

        assertThat(evaluation.relevanceScore()).isEqualTo(9);
        assertThat(evaluation.needsRetry()).isFalse();
        server.verify();
    }

    @Test
    void malformedStructuredJsonRaisesControlledException() {
        expectOllama("""
            {
              "response": "not json",
              "done": true
            }
            """, MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> service.generateQuestion(context()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("JSON");
        server.verify();
    }

    @Test
    void emptyOllamaResponseRaisesControlledException() {
        expectOllama("""
            {
              "response": "",
              "done": true
            }
            """, MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> service.generateQuestion(context()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("empty");
        server.verify();
    }

    @Test
    void ollamaHttpFailureRaisesControlledException() {
        server.expect(requestTo("http://ollama.test/api/generate"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError());

        assertThatThrownBy(() -> service.generateQuestion(context()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("Ollama is unavailable");
        server.verify();
    }

    private void expectOllama(String body, MediaType responseType) {
        server.expect(requestTo("http://ollama.test/api/generate"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.model").value("llama3.2:3b"))
            .andExpect(jsonPath("$.stream").value(false))
            .andRespond(withSuccess(body, responseType));
    }

    private InterviewContext context() {
        return new InterviewContext(
            1L,
            "Virtusa",
            "Backend Developer",
            "5+ years",
            List.of("Java"),
            List.of("Spring Boot"),
            List.of("Core Java", "Spring Boot"),
            45,
            null,
            List.of(),
            Difficulty.MEDIUM,
            45
        );
    }
}
