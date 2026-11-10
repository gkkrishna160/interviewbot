package com.interviewbot.backend.maintenance;

import java.sql.*;
import java.util.*;

public final class MigrationSafety {
  private MigrationSafety() {}

  public static void check(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (!product.contains("mysql") && !product.contains("h2"))
      throw new SQLException(
          "Schema migrations support only the configured MySQL/H2 application database");
    String catalog = c.getCatalog();
    if (c.isReadOnly()
        || catalog == null
        || catalog.isBlank()
        || Set.of("mysql", "sys", "information_schema", "performance_schema")
            .contains(catalog.toLowerCase(Locale.ROOT)))
      throw new SQLException("Database migration guard refused the target database");
  }
}
