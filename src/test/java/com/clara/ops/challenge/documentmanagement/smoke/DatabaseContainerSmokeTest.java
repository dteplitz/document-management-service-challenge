package com.clara.ops.challenge.documentmanagement.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Smoke test — Layer 1: no Spring, no JPA, pure JDBC.
 *
 * <p>Validates that: (1) Docker is up and Testcontainers can start postgres:15, (2) the init script
 * runs to completion (timezone-safe), (3) the schema and table exist.
 *
 * <p>If this test fails, the problem is below the application layer (Docker, image, init script,
 * timezone). Fix here before moving to Layer 2.
 */
class DatabaseContainerSmokeTest {

  // Singleton pattern: one start per JVM, no @Testcontainers/@Container lifecycle management.
  // Ryuk cleans up on JVM exit.
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15")
          .withEnv("TZ", "UTC")
          .withInitScript("test-schema-init.sql");

  static {
    POSTGRES.start();
  }

  @Test
  void containerStartsAndSchemaExists() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'document_schema' AND table_name = 'documents'")) {

      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).as("documents table must exist").isEqualTo(1);
    }
  }

  @Test
  void timezoneIsUTC() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SHOW TimeZone")) {

      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).as("Postgres timezone must be UTC").isEqualTo("UTC");
    }
  }
}
