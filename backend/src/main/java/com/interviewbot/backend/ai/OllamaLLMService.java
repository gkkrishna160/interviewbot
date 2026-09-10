package com.interviewbot.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewbot.backend.ai.dto.Evaluation;
import com.interviewbot.backend.ai.dto.InterviewContext;
import com.interviewbot.backend.ai.dto.InterviewPlan;
import com.interviewbot.backend.ai.dto.Question;
import com.interviewbot.backend.ai.prompt.EvaluationPromptBuilder;
import com.interviewbot.backend.ai.prompt.InterviewPlanPromptBuilder;
import com.interviewbot.backend.ai.prompt.QuestionPromptBuilder;
import com.interviewbot.backend.common.exception.LLMException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaLLMService implements LLMService {
    private static final Logger log = LoggerFactory.getLogger(OllamaLLMService.class);

    private final RestClient restClient;
    private final OllamaProperties properties;
    private final QuestionPromptBuilder questionPromptBuilder;
    private final EvaluationPromptBuilder evaluationPromptBuilder;
    private final InterviewPlanPromptBuilder interviewPlanPromptBuilder;
    private final StructuredResponseParser parser;

    @Autowired
    public OllamaLLMService(
        RestClient.Builder builder,
        OllamaProperties properties,
        QuestionPromptBuilder questionPromptBuilder,
        EvaluationPromptBuilder evaluationPromptBuilder,
        InterviewPlanPromptBuilder interviewPlanPromptBuilder,
        StructuredResponseParser parser,
        ObjectMapper objectMapper
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        MappingJackson2HttpMessageConverter ollamaJsonConverter = new MappingJackson2HttpMessageConverter(objectMapper);
        ollamaJsonConverter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM));
        RestClient configuredRestClient = builder
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .messageConverters(converters -> {
                converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                converters.add(ollamaJsonConverter);
            })
            .build();
        this.restClient = configuredRestClient;
        this.properties = properties;
        this.questionPromptBuilder = questionPromptBuilder;
        this.evaluationPromptBuilder = evaluationPromptBuilder;
        this.interviewPlanPromptBuilder = interviewPlanPromptBuilder;
        this.parser = parser;
    }

    OllamaLLMService(
        RestClient restClient,
        OllamaProperties properties,
        QuestionPromptBuilder questionPromptBuilder,
        EvaluationPromptBuilder evaluationPromptBuilder,
        InterviewPlanPromptBuilder interviewPlanPromptBuilder,
        StructuredResponseParser parser
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.questionPromptBuilder = questionPromptBuilder;
        this.evaluationPromptBuilder = evaluationPromptBuilder;
        this.interviewPlanPromptBuilder = interviewPlanPromptBuilder;
        this.parser = parser;
    }

    @Override
    public Question generateQuestion(InterviewContext context) {
        log.info(
            "Ollama question generation requested interviewId={} model={} baseUrl={} connectTimeoutSeconds={} readTimeoutSeconds={}",
            context.interviewId(),
            properties.model(),
            properties.baseUrl(),
            properties.connectTimeoutSeconds(),
            properties.readTimeoutSeconds()
        );
        long started = System.nanoTime();
        Question question = parser.parseQuestion(generate("question", questionPromptBuilder.build(context)));
        log.info("Ollama question generation parsed interviewId={} topic={} difficulty={} elapsedMs={}", context.interviewId(), question.topic(), question.difficulty(), elapsedMs(started));
        return question;
    }

    @Override
    public Evaluation evaluateAnswer(Question question, String candidateAnswer) {
        log.info("Ollama evaluation requested topic={} difficulty={} answerLength={} model={}", question.topic(), question.difficulty(), candidateAnswer.length(), properties.model());
        long started = System.nanoTime();
        Evaluation evaluation = parser.parseEvaluation(generate("evaluation", evaluationPromptBuilder.build(question, candidateAnswer)));
        log.info("Ollama evaluation parsed topic={} relevance={} correctness={} needsRetry={} elapsedMs={}", question.topic(), evaluation.relevanceScore(), evaluation.correctnessScore(), evaluation.needsRetry(), elapsedMs(started));
        return evaluation;
    }

    @Override
    public InterviewPlan createInterviewPlan(InterviewContext context) {
        log.info("Ollama interview plan requested interviewId={} model={}", context.interviewId(), properties.model());
        long started = System.nanoTime();
        InterviewPlan plan = parser.parsePlan(generate("interview plan", interviewPlanPromptBuilder.build(context)));
        log.info("Ollama interview plan parsed interviewId={} estimatedQuestionCount={} elapsedMs={}", context.interviewId(), plan.estimatedQuestionCount(), elapsedMs(started));
        return plan;
    }

    private String generate(String operation, String prompt) {
        long started = System.nanoTime();
        log.info("Calling Ollama operation={} model={} promptLength={} endpoint=/api/generate", operation, properties.model(), prompt.length());
        try {
            OllamaGenerateResponse response = restClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "model", properties.model(),
                    "prompt", prompt,
                    "format", "json",
                    "stream", false,
                    "options", Map.of("temperature", 0.2, "num_predict", 500)
                ))
                .retrieve()
                .body(OllamaGenerateResponse.class);

            if (response == null || response.response() == null || response.response().isBlank()) {
                throw new LLMException("Ollama returned an empty response");
            }
            log.info("Ollama operation={} model={} returned responseLength={} done={} elapsedMs={}", operation, properties.model(), response.response().length(), response.done(), elapsedMs(started));
            return response.response();
        } catch (RestClientException ex) {
            log.warn("Ollama operation={} model={} failed elapsedMs={} message={}", operation, properties.model(), elapsedMs(started), ex.getMessage());
            throw new LLMException("Ollama is unavailable or returned an error", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaGenerateResponse(
        String response,
        boolean done,
        @JsonProperty("total_duration") Long totalDuration
    ) {
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
