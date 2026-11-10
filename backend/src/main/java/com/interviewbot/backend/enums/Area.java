package com.interviewbot.backend.enums;

/** Trusted area identifiers and table names; never interpolate user-supplied identifiers. */
public enum Area {
  BACKEND("Backend / Java / Spring Boot", "qb_backend_question"),
  FRONTEND("Frontend / React", "qb_frontend_question"),
  AI("AI Engineering", "qb_ai_question");
  public final String label, table;

  Area(String label, String table) {
    this.label = label;
    this.table = table;
  }
}
