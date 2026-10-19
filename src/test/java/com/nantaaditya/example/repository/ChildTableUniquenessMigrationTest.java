package com.nantaaditya.example.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Runs the real Flyway migrations against a throw-away Postgres to prove V12 enforces one config row
 * per outbound system, and that it refuses to run (instead of deleting data) when duplicates exist.
 */
class ChildTableUniquenessMigrationTest {

  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void cleanSchema() {
    flyway(null).clean();
  }

  @ParameterizedTest(name = "{0} accepts only one row per outbound_system_id")
  @ValueSource(strings = {
      "outbound_resilience_configurations",
      "outbound_header_configurations",
      "outbound_auth_configurations",
      "outbound_response_mapper_configuration"})
  @DisplayName("after V12 a second row for the same outbound system is rejected (unique violation)")
  void migrate_secondRowForSameSystem_isRejected(String table) throws Exception {
    flyway(null).migrate();

    execute("INSERT INTO " + table + " (outbound_system_id) VALUES (1)");

    assertThatThrownBy(() -> execute("INSERT INTO " + table + " (outbound_system_id) VALUES (1)"))
        .isInstanceOf(SQLException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo("23505");
    // a different system is unaffected
    execute("INSERT INTO " + table + " (outbound_system_id) VALUES (2)");
  }

  @Test
  @DisplayName("rows without an outbound_system_id are not constrained")
  void migrate_nullSystemIds_remainAllowed() throws Exception {
    flyway(null).migrate();

    execute("INSERT INTO outbound_header_configurations (outbound_system_id) VALUES (NULL)");
    execute("INSERT INTO outbound_header_configurations (outbound_system_id) VALUES (NULL)");
  }

  @Test
  @DisplayName("existing duplicates abort the migration, name the table, and leave no index behind")
  void migrate_withExistingDuplicates_failsAndNamesTheTable() throws Exception {
    flyway("11").migrate();
    execute("INSERT INTO outbound_header_configurations (outbound_system_id) VALUES (5)");
    execute("INSERT INTO outbound_header_configurations (outbound_system_id) VALUES (5)");

    assertThatThrownBy(() -> flyway(null).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("outbound_header_configurations");

    assertThat(count("SELECT COUNT(*) FROM pg_indexes WHERE indexname IN ("
        + "'uq_outbound_resilience_system_id', 'uq_outbound_header_system_id', "
        + "'uq_outbound_auth_system_id', 'uq_outbound_response_mapper_system_id')")).isZero();
    // nothing was deleted
    assertThat(count("SELECT COUNT(*) FROM outbound_header_configurations")).isEqualTo(2);
  }

  private Flyway flyway(String targetVersion) {
    var config = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .cleanDisabled(false);
    if (targetVersion != null) {
      config.target(targetVersion);
    }
    return config.load();
  }

  private void execute(String sql) throws SQLException {
    try (Connection c = connection(); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private long count(String sql) throws SQLException {
    try (Connection c = connection(); Statement s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
