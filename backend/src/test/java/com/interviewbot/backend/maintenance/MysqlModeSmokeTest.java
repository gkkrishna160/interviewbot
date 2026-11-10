package com.interviewbot.backend.maintenance;

import static org.assertj.core.api.Assertions.*;

import com.interviewbot.backend.InterviewBotApplication;
import com.interviewbot.backend.agents.QuestionBankWorkflow;
import com.interviewbot.backend.dtos.*;
import com.interviewbot.backend.enums.*;
import com.interviewbot.backend.services.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in: creates and removes only its own randomly named, marker-verified scratch database. */
@EnabledIfEnvironmentVariable(named = "INTERVIEWBOT_MYSQL_SMOKE", matches = "true")
class MysqlModeSmokeTest {
  @Configuration
  static class LocalSettings {}

  @Test
  void verifiesMigrationModeIsolationAndGuardedBankWritesOnMysql() throws Exception {
    String url = System.getenv("MYSQL_SMOKE_URL"),
        user = System.getenv("MYSQL_SMOKE_USER"),
        password = System.getenv("MYSQL_SMOKE_PASSWORD");
    if (url == null) {
      try (var config =
          new SpringApplicationBuilder(LocalSettings.class).web(WebApplicationType.NONE).run()) {
        var env = config.getEnvironment();
        url = env.getRequiredProperty("spring.datasource.url");
        user = env.getRequiredProperty("spring.datasource.username");
        password = env.getProperty("spring.datasource.password", "");
      }
    }
    if (!url.startsWith("jdbc:mysql://localhost:") && !url.startsWith("jdbc:mysql://127.0.0.1:"))
      throw new IllegalArgumentException("MySQL smoke tests require an explicit local test server");
    String marker = UUID.randomUUID().toString(),
        database = "interviewbot_smoke_" + marker.replace("-", "");
    String testUrl =
        url.substring(0, url.indexOf('/', "jdbc:mysql://".length()) + 1)
            + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    try (var original = DriverManager.getConnection(url, user, password);
        var s = original.createStatement()) {
      MigrationSafety.check(original);
      s.execute("create database " + database);
    }
    try {
      try (var c = DriverManager.getConnection(testUrl, user, password);
          var s = c.createStatement()) {
        MigrationSafety.check(c);
        s.execute("create table e2e_test_marker(value varchar(36) primary key)");
        try (var p = c.prepareStatement("insert into e2e_test_marker(value) values(?)")) {
          p.setString(1, marker);
          p.executeUpdate();
        }
      }
      Flyway.configure()
          .dataSource(testUrl, user, password)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .target("4")
          .load()
          .migrate();
      try (var c = DriverManager.getConnection(testUrl, user, password);
          var s = c.createStatement()) {
        MigrationSafety.check(c);
        s.execute("create table interviews(id bigint primary key)");
        s.execute(
            "create table interview_questions(id bigint primary key,interview_id bigint,foreign"
                + " key(interview_id) references interviews(id))");
      }
      List<String> arguments =
          new ArrayList<>(
              List.of(
                  "--spring.config.import=",
                  "--spring.datasource.url=" + testUrl,
                  "--spring.datasource.username=" + user,
                  "--spring.datasource.password=" + password,
                  "--server.port=0",
                  "--question-bank.scheduling-enabled=false",
                  "--spring.flyway.placeholders.legacyBackupDirectory=target/mysql-schema-backups",
                  "--logging.file.name=target/mysql-smoke.log"));
      var gen = new ArrayList<>(arguments);
      gen.add("--app.mode=generation_mode");
      try (var app =
          new SpringApplicationBuilder(InterviewBotApplication.class)
              .run(gen.toArray(String[]::new))) {
        var bank = app.getBean(QuestionBankService.class);
        var leases = app.getBean(WorkflowLease.class);
        var lease = leases.acquire().orElseThrow();
        long id =
            bank.addManual(
                    new BankDtos.Draft(
                        "Why is String immutable in Java, and what benefits does this provide?",
                        1L,
                        "Strings",
                        BankTypes.Difficulty.BEGINNER,
                        BankTypes.QuestionType.FUNDAMENTAL,
                        List.of("java"),
                        "Essential language knowledge"))
                .id();
        bank.applyReview(
            lease,
            id,
            new BankDtos.Review(
                BankTypes.Status.APPROVED,
                95,
                95,
                1L,
                "Strings",
                BankTypes.Difficulty.BEGINNER,
                BankTypes.QuestionType.FUNDAMENTAL,
                List.of("java"),
                "Clear essential question",
                null),
            Set.of());
        leases.release(lease);
        JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select count(*) from qb_backend_question", Integer.class))
            .isEqualTo(1);
        assertThat(
                jdbc.queryForList(
                    "select table_name from information_schema.tables where table_schema=? and"
                        + " table_name in ('interviews','interview_questions','qb_question')",
                    database))
            .isEmpty();
      }
      var interview = new ArrayList<>(arguments);
      interview.add("--app.mode=interview_mode");
      try (var app =
          new SpringApplicationBuilder(InterviewBotApplication.class)
              .run(interview.toArray(String[]::new))) {
        assertThat(app.getBeansOfType(QuestionBankWorkflow.class)).isEmpty();
        var practice = app.getBean(MockInterviewService.class);
        var pool = practice.questions(new MockDtos.Selection(List.of(Area.BACKEND, Area.AI)));
        assertThat(pool.total()).isEqualTo(20);
        assertThat(pool.questions()).hasSize(1);
        assertThatThrownBy(
                () ->
                    app.getBean(QuestionBankService.class)
                        .addTopic(2, new BankDtos.NameRequest("Not allowed")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      }
    } finally {
      try (var c = DriverManager.getConnection(testUrl, user, password);
          var s = c.createStatement()) {
        MigrationSafety.check(c);
        try (var r = s.executeQuery("select database(),value from e2e_test_marker")) {
          assertThat(r.next()).isTrue();
          if (!database.equals(r.getString(1)) || !marker.equals(r.getString(2)))
            throw new IllegalStateException("Scratch database identity check failed");
        }
        if (!database.startsWith("interviewbot_smoke_")
            || !database.matches("interviewbot_smoke_[a-f0-9]{32}"))
          throw new IllegalStateException("Unsafe cleanup target");
        s.execute("drop database " + database);
      }
    }
  }
}
