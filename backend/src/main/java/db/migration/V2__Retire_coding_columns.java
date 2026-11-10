package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve historical data while allowing inserts from the technical-only interview mapping. */
public class V2__Retire_coding_columns extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection c = context.getConnection();
    if (!c.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) return;
    try (Statement s = c.createStatement()) {
      if (column(c, "interviews", "status")) {
        s.execute("alter table interviews modify status varchar(32) not null");
        s.executeUpdate(
            "update interviews set status='COMPLETED',"
                + " completed_at=coalesce(completed_at,current_timestamp) where status='CODING'");
      }
      if (column(c, "interviews", "mode")) {
        s.execute("alter table interviews modify mode varchar(32)");
        s.executeUpdate("update interviews set mode='TECHNICAL' where mode in ('CODING','MIXED')");
      }
      String[][] defaults = {
        {"coding_enabled", "0"},
        {"coding_difficulty", "'MEDIUM'"},
        {"coding_problem_count", "1"},
        {"coding_duration_minutes", "30"}
      };
      for (String[] entry : defaults)
        if (column(c, "interviews", entry[0]))
          s.execute("alter table interviews alter column " + entry[0] + " set default " + entry[1]);
    }
  }

  private boolean column(Connection c, String table, String name) throws SQLException {
    try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, name)) {
      return rs.next();
    }
  }
}
