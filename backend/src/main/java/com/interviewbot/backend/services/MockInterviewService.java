package com.interviewbot.backend.services;

import static com.interviewbot.backend.dtos.MockDtos.*;

import com.interviewbot.backend.agents.InterviewAgents;
import com.interviewbot.backend.config.AppProperties;
import com.interviewbot.backend.enums.Area;
import com.interviewbot.backend.security.PromptGuard;
import com.interviewbot.backend.security.QuestionTickets;
import jakarta.validation.Validator;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stateless: bank reads, one optional question request, and one independent assessment. */
@Service
public class MockInterviewService {
  private final JdbcTemplate jdbc;
  private final AppProperties mode;
  private final QuestionTickets tickets;
  private final InterviewAgents agents;
  private final PromptGuard guard;
  private final Validator validator;
  private final DuplicateDetector duplicates;

  public MockInterviewService(
      JdbcTemplate jdbc,
      AppProperties mode,
      QuestionTickets tickets,
      InterviewAgents agents,
      PromptGuard guard,
      Validator validator,
      DuplicateDetector duplicates) {
    this.jdbc = jdbc;
    this.mode = mode;
    this.tickets = tickets;
    this.agents = agents;
    this.guard = guard;
    this.validator = validator;
    this.duplicates = duplicates;
  }

  public Config config() {
    return new Config(
        mode.mode().name().toLowerCase(Locale.ROOT),
        10,
        Arrays.stream(Area.values())
            .map(
                a ->
                    new AreaView(
                        a,
                        a.label,
                        jdbc.queryForObject(
                            "select count(*) from "
                                + a.table
                                + " q join qb_topic t on t.id=q.topic_id join qb_category c on"
                                + " c.id=t.category_id where q.status='APPROVED' and t.enabled=true"
                                + " and c.enabled=true and c.bank=?",
                            Long.class,
                            a.name()),
                        10))
            .toList());
  }

  public Questions questions(Selection selection) {
    mode.requireInterview();
    validate(selection);
    if (selection.areas().size() != new HashSet<>(selection.areas()).size())
      throw new IllegalArgumentException("Select each area once");
    List<Question> questions = new ArrayList<>();
    List<Shortfall> missing = new ArrayList<>();
    for (var area : selection.areas()) {
      var sampled =
          jdbc.query(
              "select q.id,q.text,t.name as topic,q.subtopic,q.difficulty,q.question_type from "
                  + area.table
                  + " q join qb_topic t on t.id=q.topic_id join qb_category c on c.id=t.category_id"
                  + " where q.status='APPROVED' and t.enabled=true and c.enabled=true and c.bank=?"
                  + " order by rand() limit 10",
              (r, n) ->
                  new Data(
                      "bank-" + r.getLong("id"),
                      area,
                      r.getString("text"),
                      r.getString("topic"),
                      r.getString("subtopic"),
                      com.interviewbot.backend.enums.BankTypes.Difficulty.valueOf(
                          r.getString("difficulty")),
                      com.interviewbot.backend.enums.BankTypes.QuestionType.valueOf(
                          r.getString("question_type")),
                      "BANK"),
              area.name());
      int safe = 0;
      for (var q : sampled) {
        try {
          validate(q);
          guard.checkText(q.text());
          questions.add(tickets.sign(q));
          safe++;
        } catch (IllegalArgumentException unsafe) {
          /* Unsafe bank content is never served; fallback fills the slot. */
        }
      }
      if (safe < 10) missing.add(new Shortfall(area, 10 - safe));
    }
    Collections.shuffle(questions, new SecureRandom());
    return new Questions(questions, missing, selection.areas().size() * 10);
  }

  public Question next(Next request) {
    mode.requireInterview();
    validate(request);
    var previous =
        request.previousQuestions().stream()
            .map(tickets::verify)
            .filter(q -> q.area() == request.area())
            .toList();
    previous.forEach(q -> guard.checkText(q.text()));
    var topics =
        jdbc.queryForList(
            "select t.name from qb_topic t join qb_category c on c.id=t.category_id where c.bank=?"
                + " and t.enabled=true and c.enabled=true order by t.id",
            String.class,
            request.area().name());
    if (topics.isEmpty()) throw new IllegalArgumentException("No enabled topics for this area");
    var output =
        agents.question(
            Map.of(
                "area",
                request.area(),
                "allowedTopics",
                topics,
                "alreadyAsked",
                previous.stream()
                    .map(q -> q.text().substring(0, Math.min(700, q.text().length())))
                    .toList(),
                "questionNumberInArea",
                previous.size() + 1));
    validateOutput(output);
    try {
      guard.checkText(output.text());
    } catch (IllegalArgumentException ex) {
      throw new com.interviewbot.backend.exceptions.LLMException(
          "Interview agent returned unsafe content", ex);
    }
    if (output.area() != request.area() || !topics.contains(output.topic()))
      throw new com.interviewbot.backend.exceptions.LLMException(
          "Interview agent returned an out-of-scope question; retry");
    if (previous.stream()
        .anyMatch(
            q ->
                duplicates.fingerprint(q.text()).equals(duplicates.fingerprint(output.text()))
                    || duplicates.similarity(q.text(), output.text()) > 0.9))
      throw new com.interviewbot.backend.exceptions.LLMException(
          "Interview agent repeated a question; retry for a different question");
    return tickets.sign(
        new Data(
            "live-" + UUID.randomUUID(),
            output.area(),
            output.text(),
            output.topic(),
            output.subtopic(),
            output.difficulty(),
            output.questionType(),
            "INTERVIEW_AGENT"));
  }

  public Judgment assess(Assessment request) {
    mode.requireInterview();
    validate(request);
    Data question = tickets.verify(request.questionToken());
    guard.checkText(question.text());
    guard.checkText(request.answer());
    var result =
        agents.judge(
            Map.of(
                "question",
                question.text(),
                "area",
                question.area(),
                "topic",
                question.topic(),
                "difficulty",
                question.difficulty(),
                "candidateAnswer",
                request.answer()));
    validateOutput(result);
    if (!Double.isFinite(result.score()))
      throw new com.interviewbot.backend.exceptions.LLMException("Judge returned an invalid score");
    return result;
  }

  private void validateOutput(Object value) {
    if (value == null || !validator.validate(value).isEmpty())
      throw new com.interviewbot.backend.exceptions.LLMException(
          "Interview agent returned an invalid assessment or question");
  }

  private void validate(Object value) {
    if (value == null || !validator.validate(value).isEmpty())
      throw new IllegalArgumentException("Invalid interview input or agent result");
  }
}
