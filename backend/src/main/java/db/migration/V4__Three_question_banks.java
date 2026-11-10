package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Split payloads without changing any public question ID or losing historical references. */
public class V4__Three_question_banks extends BaseJavaMigration {
  private static final String COLUMNS =
      "id,created_at,updated_at,version,text,fingerprint,topic_id,subtopic,difficulty,question_type,status,source,quality_score,relevance_score,review_attempts,generation_rationale,batch_id,duplicate_of_id";
  private static final Map<String, String> TABLES = new LinkedHashMap<>();

  static {
    TABLES.put("BACKEND", "qb_backend_question");
    TABLES.put("FRONTEND", "qb_frontend_question");
    TABLES.put("AI", "qb_ai_question");
  }

  @Override
  public void migrate(Context context) throws Exception {
    Connection c = context.getConnection();
    if (!column(c, "qb_category", "bank"))
      execute(c, "alter table qb_category add column bank varchar(16) not null default 'BACKEND'");
    regroup(c);
    execute(
        c,
        "create table if not exists qb_question_identity (id bigint auto_increment primary key,"
            + " bank varchar(16) not null, constraint uq_qb_identity_bank unique(id,bank),"
            + " constraint ck_qb_identity_bank check(bank in ('BACKEND','FRONTEND','AI')))");
    for (var entry : TABLES.entrySet()) {
      String bank = entry.getKey(),
          table = entry.getValue(),
          prefix = "qb_" + bank.toLowerCase(Locale.ROOT);
      execute(
          c,
          "create table if not exists "
              + table
              + " (id bigint primary key,created_at timestamp(6) not null,updated_at timestamp(6)"
              + " not null,version bigint not null default 0,text text not null,fingerprint"
              + " varchar(64) not null,topic_id bigint not null,subtopic varchar(100),difficulty"
              + " varchar(24) not null,question_type varchar(24) not null,status varchar(24) not"
              + " null,source varchar(24) not null,quality_score int,relevance_score"
              + " int,review_attempts int not null default 0,generation_rationale text,batch_id"
              + " bigint,duplicate_of_id bigint,bank varchar(16) not null,constraint ck_"
              + prefix
              + "_bank check(bank='"
              + bank
              + "'),"
              + "constraint ck_"
              + prefix
              + "_quality check(quality_score between 0 and 100),"
              + "constraint ck_"
              + prefix
              + "_relevance check(relevance_score between 0 and 100),"
              + "constraint ck_"
              + prefix
              + "_status check(status in ('PENDING_REVIEW','APPROVED','REJECTED','DUPLICATE')),"
              + "constraint ck_"
              + prefix
              + "_difficulty check(difficulty in ('BEGINNER','INTERMEDIATE','ADVANCED')),foreign"
              + " key(id,bank) references qb_question_identity(id,bank),foreign key(topic_id)"
              + " references qb_topic(id),foreign key(batch_id) references"
              + " qb_generation_batch(id),foreign key(duplicate_of_id) references"
              + " qb_question_identity(id),index idx_"
              + prefix
              + "_browse(status,topic_id,difficulty,question_type),"
              + "index idx_"
              + prefix
              + "_fingerprint(fingerprint),index idx_"
              + prefix
              + "_pending(status,review_attempts,created_at))");
    }
    execute(
        c,
        "insert into qb_question_identity(id,bank) select q.id,c.bank from qb_question q join"
            + " qb_topic t on t.id=q.topic_id join qb_category c on c.id=t.category_id where not"
            + " exists(select 1 from qb_question_identity i where i.id=q.id)");
    for (var entry : TABLES.entrySet()) {
      String selected =
          Arrays.stream(COLUMNS.split(","))
              .map(x -> "q." + x)
              .collect(java.util.stream.Collectors.joining(","));
      execute(
          c,
          "insert into "
              + entry.getValue()
              + " ("
              + COLUMNS
              + ",bank) select "
              + selected
              + ",c.bank from qb_question q join qb_topic t on t.id=q.topic_id join qb_category c"
              + " on c.id=t.category_id where c.bank='"
              + entry.getKey()
              + "' and not exists(select 1 from "
              + entry.getValue()
              + " n where n.id=q.id)");
      long missing =
          number(
              c,
              "select count(*) from qb_question q join qb_topic t on t.id=q.topic_id join"
                  + " qb_category c on c.id=t.category_id left join "
                  + entry.getValue()
                  + " n on n.id=q.id where c.bank='"
                  + entry.getKey()
                  + "' and (n.id is null or n.text<>q.text or n.status<>q.status or"
                  + " n.version<>q.version or n.topic_id<>q.topic_id)");
      if (missing != 0)
        throw new SQLException("Question bank copy verification failed for " + entry.getKey());
    }
    // AUTO_INCREMENT advances on explicit IDs in MySQL; H2 needs its identity reset explicitly.
    if (!mysql(c))
      execute(
          c,
          "alter table qb_question_identity alter column id restart with "
              + (number(c, "select coalesce(max(id),0)+1 from qb_question_identity")));
    redirectForeignKeys(c, "qb_validation");
    redirectForeignKeys(c, "qb_agent_execution");
    redirectForeignKeys(c, "qb_question_tag");
    // qb_question is retained as an untouched pre-split backup. No application mapping writes it.
  }

  private void regroup(Connection c) throws SQLException {
    record Topic(long id, String name, String category, long categoryId) {}
    List<Topic> topics = new ArrayList<>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "select t.id,t.name,c.name,t.category_id from qb_topic t join qb_category c on"
                    + " c.id=t.category_id order by t.id")) {
      while (r.next())
        topics.add(new Topic(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4)));
    }
    for (var t : topics) {
      String name = (t.name() + " " + t.category()).toLowerCase(Locale.ROOT);
      long category =
          t.categoryId() == 3 || name.matches(".*(frontend|react|javascript|browser|html|css).*")
              ? 3
              : name.matches(
                      ".*(agentic|applied ai|ai engineering|artificial intelligence|machine"
                          + " learning|llm|generative ai).*")
                  ? 4
                  : 2;
      String topicName = t.name();
      try (var ps =
          c.prepareStatement(
              "select count(*) from qb_topic where category_id=? and lower(name)=lower(?) and"
                  + " id<>?")) {
        ps.setLong(1, category);
        ps.setString(2, topicName);
        ps.setLong(3, t.id());
        try (var r = ps.executeQuery()) {
          r.next();
          if (r.getInt(1) > 0)
            topicName =
                topicName.substring(0, Math.min(75, topicName.length())) + " (" + t.id() + ")";
        }
      }
      try (var ps = c.prepareStatement("update qb_topic set category_id=?,name=? where id=?")) {
        ps.setLong(1, category);
        ps.setString(2, topicName);
        ps.setLong(3, t.id());
        ps.executeUpdate();
      }
    }
    execute(c, "update qb_category set enabled=false");
    execute(
        c,
        "update qb_category set name='Backend / Java / Spring Boot',bank='BACKEND',enabled=true"
            + " where id=2");
    execute(
        c,
        "update qb_category set name='Frontend / React',bank='FRONTEND',enabled=true where id=3");
    execute(c, "update qb_category set name='AI Engineering',bank='AI',enabled=true where id=4");
    execute(
        c,
        "insert into qb_topic(category_id,name,enabled,created_at,updated_at,version) select"
            + " 4,'Applied AI',true,current_timestamp,current_timestamp,0 where not exists(select 1"
            + " from qb_topic where category_id=4 and name='Applied AI')");
  }

  private void redirectForeignKeys(Connection c, String table) throws SQLException {
    Map<String, String> keys = new LinkedHashMap<>();
    try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), null, table)) {
      while (r.next())
        if ("qb_question".equalsIgnoreCase(r.getString("PKTABLE_NAME")))
          keys.put(r.getString("FK_NAME"), r.getString("FKCOLUMN_NAME"));
    }
    for (var key : keys.entrySet()) {
      String quote = mysql(c) ? "`" : "\"";
      execute(
          c,
          "alter table "
              + table
              + (mysql(c) ? " drop foreign key " : " drop constraint ")
              + quote
              + key.getKey()
              + quote);
    }
    // Also resumes after MySQL committed a DROP but before the replacement ADD.
    List<String> columns =
        table.equals("qb_validation")
            ? List.of("question_id", "duplicate_of_id")
            : List.of("question_id");
    for (String col : columns) {
      boolean exists = false;
      try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), null, table)) {
        while (r.next())
          if ("qb_question_identity".equalsIgnoreCase(r.getString("PKTABLE_NAME"))
              && col.equalsIgnoreCase(r.getString("FKCOLUMN_NAME"))) exists = true;
      }
      if (!exists)
        execute(
            c,
            "alter table "
                + table
                + " add constraint fk_"
                + table
                + "_"
                + col
                + " foreign key("
                + col
                + ") references qb_question_identity(id)");
    }
  }

  private boolean mysql(Connection c) throws SQLException {
    return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
  }

  private boolean column(Connection c, String table, String col) throws SQLException {
    try (var r = c.getMetaData().getColumns(c.getCatalog(), null, table, col)) {
      return r.next();
    }
  }

  private void execute(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private long number(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }
}
