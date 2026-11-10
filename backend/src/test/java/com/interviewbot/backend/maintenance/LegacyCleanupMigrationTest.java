package com.interviewbot.backend.maintenance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import db.migration.V5__Retire_unused_empty_tables;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class LegacyCleanupMigrationTest {
  @TempDir Path directory;

  private Connection fixture() throws Exception {
    String url =
        "jdbc:h2:mem:retire_"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
    Connection c = DriverManager.getConnection(url, "sa", "");
    try (var s = c.createStatement()) {
      s.execute("create table interviews(id bigint primary key)");
      s.execute(
          "create table interview_questions(id bigint primary key,interview_id bigint,foreign"
              + " key(interview_id) references interviews(id))");
    }
    return c;
  }

  private Context context(Connection c, Path backup) {
    var context = mock(Context.class);
    var config = mock(org.flywaydb.core.api.configuration.Configuration.class);
    when(context.getConnection()).thenReturn(c);
    when(context.getConfiguration()).thenReturn(config);
    when(config.getPlaceholders()).thenReturn(Map.of("legacyBackupDirectory", backup.toString()));
    return context;
  }

  private boolean table(Connection c, String name) throws Exception {
    try (var r = c.getMetaData().getTables(c.getCatalog(), null, name, new String[] {"TABLE"})) {
      return r.next();
    }
  }

  @Test
  void archivesEmptySchemasAndDropsOnlyRetiredTablesInForeignKeyOrder() throws Exception {
    try (var c = fixture()) {
      new V5__Retire_unused_empty_tables().migrate(context(c, directory));
      assertThat(table(c, "interviews")).isFalse();
      assertThat(table(c, "interview_questions")).isFalse();
      assertThat(table(c, "qb_question")).isFalse();
      assertThat(table(c, "qb_backend_question")).isTrue();
      assertThat(table(c, "qb_validation")).isTrue();
      Path sql;
      try (var files = Files.list(directory)) {
        sql = files.filter(p -> p.toString().endsWith(".sql")).findFirst().orElseThrow();
      }
      String digest =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)));
      String checksum = Files.readString(Path.of(sql.toString().replace(".sql", ".sha256")));
      assertThat(checksum).startsWith(digest);
      ScriptUtils.executeSqlScript(c, new ByteArrayResource(Files.readAllBytes(sql)));
      assertThat(table(c, "interviews")).isTrue();
      assertThat(table(c, "interview_questions")).isTrue();
    }
  }

  @Test
  void populatedLegacyDataBlocksAllDrops() throws Exception {
    try (var c = fixture();
        var s = c.createStatement()) {
      s.executeUpdate("insert into interviews(id) values(1)");
      assertThatThrownBy(() -> new V5__Retire_unused_empty_tables().migrate(context(c, directory)))
          .hasMessageContaining("historical records");
      assertThat(table(c, "qb_question")).isTrue();
      assertThat(table(c, "interview_questions")).isTrue();
      try (var r = s.executeQuery("select count(*) from interviews")) {
        r.next();
        assertThat(r.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void activeExternalReferencesBlockCleanup() throws Exception {
    try (var c = fixture();
        var s = c.createStatement()) {
      s.execute(
          "create table active_dependency(id bigint,old_id bigint,foreign key(old_id) references"
              + " interviews(id))");
      assertThatThrownBy(() -> new V5__Retire_unused_empty_tables().migrate(context(c, directory)))
          .hasMessageContaining("active reference");
      assertThat(table(c, "interviews")).isTrue();
      assertThat(table(c, "qb_question")).isTrue();
    }
  }

  @Test
  void failedArchivePreventsDatabaseChanges() throws Exception {
    try (var c = fixture()) {
      Path file = directory.resolve("not-a-directory");
      Files.writeString(file, "existing");
      assertThatThrownBy(() -> new V5__Retire_unused_empty_tables().migrate(context(c, file)))
          .isInstanceOf(java.io.IOException.class);
      assertThat(table(c, "interviews")).isTrue();
      assertThat(table(c, "qb_question")).isTrue();
    }
  }
}
