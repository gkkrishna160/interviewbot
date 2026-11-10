package com.interviewbot.backend.maintenance;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class QuestionBankMigrationResumeTest {
  private String url() {
    return "jdbc:h2:mem:migration_"
        + UUID.randomUUID()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  }

  private String initialMigration() throws Exception {
    return new ClassPathResource("db/migration/V1__question_bank.sql")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"+05:30", "+14:00", "-12:00"})
  void initialLeaseIsExpiredAndValidAcrossTimezones(String timezone) throws Exception {
    try (Connection c = DriverManager.getConnection(url(), "sa", "")) {
      c.createStatement().execute("set time zone '" + timezone + "'");
      ScriptUtils.executeSqlScript(
          c, new ByteArrayResource(initialMigration().getBytes(StandardCharsets.UTF_8)));
      try (var rs =
          c.createStatement()
              .executeQuery(
                  "select expires_at<current_timestamp,next_allowed_at<current_timestamp from"
                      + " qb_workflow_lock where id=1")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean(1)).isTrue();
        assertThat(rs.getBoolean(2)).isTrue();
      }
    }
  }

  @Test
  void resumesAlreadyCreatedTablesAndIndexesBeforeTheFailedLockSeed() throws Exception {
    String url = url(), script = initialMigration();
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      String prefix = script.substring(0, script.indexOf("insert into qb_workflow_lock"));
      ScriptUtils.executeSqlScript(
          c, new ByteArrayResource(prefix.getBytes(StandardCharsets.UTF_8)));
    }
    var flyway =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .locations("classpath:db/migration")
            .load();
    flyway.migrate();
    flyway.validate();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      try (var rs = s.executeQuery("select count(*) from qb_topic")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(38);
      }
      try (var rs = s.executeQuery("select count(*) from qb_workflow_lock")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void rerunningInitialSqlPreservesExistingSeedsAndLeaseState() throws Exception {
    try (Connection c = DriverManager.getConnection(url(), "sa", "")) {
      var migration = new ByteArrayResource(initialMigration().getBytes(StandardCharsets.UTF_8));
      ScriptUtils.executeSqlScript(c, migration);
      c.createStatement().execute("update qb_workflow_lock set token=7,owner='existing-worker'");
      ScriptUtils.executeSqlScript(c, migration);
      try (var rs =
          c.createStatement().executeQuery("select token,owner from qb_workflow_lock where id=1")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(7);
        assertThat(rs.getString(2)).isEqualTo("existing-worker");
      }
      try (var rs = c.createStatement().executeQuery("select count(*) from qb_topic")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(36);
      }
    }
  }
}
