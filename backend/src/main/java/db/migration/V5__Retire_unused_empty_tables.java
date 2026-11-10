package db.migration;

import com.interviewbot.backend.maintenance.MigrationSafety;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;

/** Retire only verified-empty unused tables; never discard populated legacy history. */
public class V5__Retire_unused_empty_tables extends BaseJavaMigration {
  private static final Set<String> RETIRED =
      Set.of(
          "interviews",
          "interview_questions",
          "candidate_answers",
          "evaluations",
          "evaluation_entity_missing_concepts",
          "evaluation_entity_strengths",
          "evaluation_entity_weaknesses",
          "interview_frameworks",
          "interview_languages",
          "interview_technologies",
          "interview_topics",
          "question_expected_concepts",
          "skill_assessments",
          "qb_question");

  @Override
  public void migrate(Context context) throws Exception {
    Connection c = context.getConnection();
    MigrationSafety.check(c);
    Set<String> all = new LinkedHashSet<>();
    try (var r = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
      while (r.next()) all.add(r.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
    }
    Set<String> retired = new TreeSet<>(all);
    retired.retainAll(RETIRED);
    if (retired.isEmpty()) return;
    Map<String, Set<String>> children = new HashMap<>();
    retired.forEach(t -> children.put(t, new HashSet<>()));
    for (String table : all)
      try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), null, table)) {
        while (r.next()) {
          String parent = r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
          if (retired.contains(parent) && !parent.equals(table)) {
            if (!retired.contains(table))
              throw new SQLException(
                  "Retired table "
                      + parent
                      + " still has an active reference from "
                      + table
                      + "; no tables were removed");
            children.get(parent).add(table);
          }
        }
      }
    List<String> order = new ArrayList<>();
    Set<String> pending = new TreeSet<>(retired);
    while (!pending.isEmpty()) {
      String next =
          pending.stream()
              .filter(t -> children.get(t).stream().noneMatch(pending::contains))
              .findFirst()
              .orElseThrow(
                  () ->
                      new SQLException(
                          "Retired tables contain a foreign-key cycle; no tables were removed"));
      order.add(next);
      pending.remove(next);
    }
    boolean mysql =
        c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
    // Check before acquiring locks as well, so a populated history gives a clear, non-destructive
    // failure.
    empty(c, retired);
    StringBuilder schema =
        new StringBuilder(
            "-- Verified-empty retired tables only. Restore parents before children.\n");
    List<String> restore = new ArrayList<>(order);
    Collections.reverse(restore);
    for (String table : restore) {
      if (mysql) {
        try (var s = c.createStatement();
            var r = s.executeQuery("show create table `" + table + "`")) {
          r.next();
          schema.append(r.getString(2)).append(";\n");
        }
      } else {
        try (var s = c.createStatement();
            var r = s.executeQuery("script nodata table " + table)) {
          while (r.next()) {
            String sql = r.getString(1).trim();
            if (sql.matches(
                "(?is)^(CREATE (?:MEMORY |CACHED )?TABLE|ALTER TABLE|CREATE (?:UNIQUE )?INDEX).*"))
              schema.append(sql).append('\n');
          }
        }
      }
    }
    Path directory =
        Path.of(
                context
                    .getConfiguration()
                    .getPlaceholders()
                    .getOrDefault("legacyBackupDirectory", "backups"))
            .toAbsolutePath()
            .normalize();
    if (directory.getParent() == null || directory.getNameCount() < 2)
      throw new SQLException("Unsafe database schema archive directory");
    Files.createDirectories(directory);
    String name = "retired-empty-schema-" + UUID.randomUUID();
    Path backup = directory.resolve(name + ".sql");
    byte[] bytes = schema.toString().getBytes(StandardCharsets.UTF_8);
    Files.write(backup, bytes, StandardOpenOption.CREATE_NEW);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Files.writeString(
        directory.resolve(name + ".sha256"),
        digest + "  " + backup.getFileName() + "\n",
        StandardOpenOption.CREATE_NEW);
    if (!MessageDigest.isEqual(bytes, Files.readAllBytes(backup)))
      throw new SQLException("Retired schema archive verification failed; no tables were removed");
    boolean locked = false;
    try {
      if (mysql) {
        try (var s = c.createStatement()) {
          s.execute(
              "lock tables "
                  + retired.stream()
                      .map(t -> "`" + t + "` write")
                      .collect(java.util.stream.Collectors.joining(",")));
          locked = true;
        }
      }
      empty(c, retired); // Re-check under write locks before the first DROP.
      for (String table : order) {
        if (!RETIRED.contains(table)) throw new SQLException("Cleanup allowlist violation");
        try (var s = c.createStatement()) {
          s.execute("drop table " + (mysql ? "`" + table + "`" : table));
        }
      }
    } finally {
      if (locked)
        try (var s = c.createStatement()) {
          s.execute("unlock tables");
        }
    }
  }

  private void empty(Connection c, Set<String> tables) throws SQLException {
    for (String table : tables)
      try (var s = c.createStatement();
          var r = s.executeQuery("select count(*) from " + table)) {
        r.next();
        if (r.getLong(1) != 0)
          throw new SQLException(
              "Retired table "
                  + table
                  + " contains historical records. Export or migrate that history before cleanup;"
                  + " no tables were removed.");
      }
  }
}
