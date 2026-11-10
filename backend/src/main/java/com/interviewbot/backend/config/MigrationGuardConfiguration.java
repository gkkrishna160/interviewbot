package com.interviewbot.backend.config;

import com.interviewbot.backend.maintenance.MigrationSafety;
import org.flywaydb.core.api.callback.*;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;

@Configuration
public class MigrationGuardConfiguration {
  @Bean
  FlywayMigrationStrategy guardedMigrations(
      @org.springframework.beans.factory.annotation.Value("${app.database.allow-migrations:true}")
          boolean allowed) {
    return flyway -> {
      if (!allowed)
        throw new IllegalStateException(
            "Database migrations are disabled by app.database.allow-migrations");
      try (var c = flyway.getConfiguration().getDataSource().getConnection()) {
        MigrationSafety.check(c);
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException("Database migration preflight failed", e);
      }
      flyway.migrate();
    };
  }

  @Bean
  Callback migrationWriteGuard() {
    return new Callback() {
      public boolean supports(Event event, Context c) {
        return event == Event.BEFORE_MIGRATE
            || event == Event.BEFORE_EACH_MIGRATE
            || event == Event.BEFORE_BASELINE;
      }

      public boolean canHandleInTransaction(Event e, Context c) {
        return true;
      }

      public String getCallbackName() {
        return "application-database-write-guard";
      }

      public void handle(Event e, Context c) {
        try {
          MigrationSafety.check(c.getConnection());
        } catch (java.sql.SQLException ex) {
          throw new IllegalStateException("Database guard refused schema changes", ex);
        }
      }
    };
  }
}
