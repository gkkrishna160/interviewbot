package com.interviewbot.backend.maintenance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import db.migration.V4__Three_question_banks;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class ThreeQuestionBanksMigrationTest {
  @Test
  void populatedUpgradePreservesAllPayloadsReferencesAndCanResume() throws Exception {
    String url =
        "jdbc:h2:mem:split_"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
    try (var c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.executeUpdate(
          "insert into"
              + " qb_generation_batch(id,created_at,updated_at,version,idempotency_key,topic_id,requested_count,generated_count,status,fence_token)"
              + " values(5,current_timestamp,current_timestamp,0,'old-batch',1,3,3,'COMPLETED',1)");
      for (long[] row : new long[][] {{11, 1}, {22, 22}, {33, 37}}) {
        s.executeUpdate(
            "insert into"
                + " qb_question(id,created_at,updated_at,version,text,fingerprint,topic_id,subtopic,difficulty,question_type,status,source,quality_score,relevance_score,review_attempts,generation_rationale,batch_id)"
                + " values("
                + row[0]
                + ",'2026-01-01 10:00:00','2026-02-01 11:00:00',7,'Historical question "
                + row[0]
                + "','fingerprint-"
                + row[0]
                + "',"
                + row[1]
                + ",'Preserved"
                + " subtopic','ADVANCED','SCENARIO','APPROVED','AI_GENERATED',95,94,2,'Preserved"
                + " rationale',5)");
      }
      s.executeUpdate("update qb_question set status='DUPLICATE',duplicate_of_id=11 where id=33");
      s.executeUpdate(
          "insert into qb_tag(id,created_at,updated_at,version,name)"
              + " values(9,current_timestamp,current_timestamp,0,'preserved')");
      s.executeUpdate("insert into qb_question_tag(question_id,tag_id) values(22,9)");
      s.executeUpdate(
          "insert into"
              + " qb_validation(created_at,updated_at,version,question_id,previous_status,decision,reason,reviewer,model,duplicate_of_id)"
              + " values(current_timestamp,current_timestamp,0,33,'PENDING_REVIEW','DUPLICATE','Preserved"
              + " audit','reviewer','model',11)");
      s.executeUpdate(
          "insert into"
              + " qb_agent_execution(created_at,updated_at,version,agent,model,status,attempt,fence_token,batch_id,question_id)"
              + " values(current_timestamp,current_timestamp,0,'question_validation','model','SUCCEEDED',1,1,5,22)");
    }
    var flyway = Flyway.configure().dataSource(url, "sa", "").target("4").load();
    flyway.migrate();
    flyway.validate();
    try (var c = DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      for (String table :
          new String[] {"qb_backend_question", "qb_frontend_question", "qb_ai_question"}) {
        assertThat(count(c, "select count(*) from " + table)).isEqualTo(1);
        try (var r =
            s.executeQuery(
                "select q.*,n.bank from qb_question q join " + table + " n on n.id=q.id")) {
          assertThat(r.next()).isTrue();
          long id = r.getLong("id");
          try (var copy =
              c.createStatement().executeQuery("select * from " + table + " where id=" + id)) {
            assertThat(copy.next()).isTrue();
            for (String col :
                new String[] {
                  "text",
                  "fingerprint",
                  "topic_id",
                  "subtopic",
                  "difficulty",
                  "question_type",
                  "status",
                  "source",
                  "quality_score",
                  "relevance_score",
                  "review_attempts",
                  "generation_rationale",
                  "batch_id",
                  "duplicate_of_id",
                  "created_at",
                  "updated_at",
                  "version"
                })
              assertThat(copy.getString(col)).as(table + "." + col).isEqualTo(r.getString(col));
          }
        }
      }
      assertThat(count(c, "select count(*) from qb_question")).isEqualTo(3);
      assertThat(
              count(
                  c,
                  "select count(*) from qb_validation where question_id=33 and duplicate_of_id=11"))
          .isEqualTo(1);
      assertThat(count(c, "select count(*) from qb_agent_execution where question_id=22"))
          .isEqualTo(1);
      assertThat(count(c, "select count(*) from qb_question_tag where question_id=22 and tag_id=9"))
          .isEqualTo(1);
      assertThat(count(c, "select count(*) from qb_category where enabled=true")).isEqualTo(3);
      assertThat(count(c, "select count(*) from qb_topic")).isEqualTo(38);
      Context context = mock(Context.class);
      when(context.getConnection()).thenReturn(c);
      new V4__Three_question_banks().migrate(context);
      assertThat(count(c, "select count(*) from qb_question_identity")).isEqualTo(3);
      s.executeUpdate("insert into qb_question_identity(bank) values('AI')");
      long id = count(c, "select max(id) from qb_question_identity");
      assertThat(id).isGreaterThan(33);
      // All three old reference tables accept newly allocated IDs absent from the backup.
      s.executeUpdate("insert into qb_question_tag(question_id,tag_id) values(" + id + ",9)");
      s.executeUpdate("update qb_validation set question_id=" + id + ",duplicate_of_id=" + id);
      s.executeUpdate("update qb_agent_execution set question_id=" + id);
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into qb_question_tag(question_id,tag_id) values(999999,9)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () -> s.executeUpdate("update qb_ai_question set bank='FRONTEND' where id=33"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "insert into qb_backend_question select * from qb_frontend_question"))
          .isInstanceOf(SQLException.class);
    }
  }

  private long count(Connection c, String query) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(query)) {
      r.next();
      return r.getLong(1);
    }
  }
}
