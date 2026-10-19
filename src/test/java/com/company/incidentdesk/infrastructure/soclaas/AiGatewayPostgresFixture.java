package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;
import com.zaxxer.hikari.HikariDataSource;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
abstract class AiGatewayPostgresFixture {
    protected static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    private static final String SCHEMA = "gateway_test_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin;
    private static boolean schemaCreated;
    protected static JdbcTemplate jdbc;
    protected static HikariDataSource source;
    protected static DataSourceTransactionManager manager;
    protected final MutableClock clock = new MutableClock(NOW);

    @BeforeAll
    static void migrateIsolatedSchema() {
        assertEquals("true", System.getenv("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"),
                "Explicit isolated database confirmation is required");
        var database = NeonDatabaseUrl.parse(System.getenv("INCIDENT_DESK_TEST_DATABASE_URL"));
        var adminSource = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE SCHEMA " + SCHEMA);
        schemaCreated = true;
        String url = database.jdbcUrl() + (database.jdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA;
        source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(database.username());
        source.setPassword(database.password());
        source.setMinimumIdle(0);
        source.setMaximumPoolSize(3);
        source.setConnectionTimeout(3000);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas(SCHEMA).defaultSchema(SCHEMA).load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
    }

    @AfterAll
    static void dropOnlyOwnedSchema() {
        try {
            if (source != null) {
                source.close();
            }
        } finally {
            if (schemaCreated) {
                admin.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                schemaCreated = false;
            }
        }
    }

    @BeforeEach
    void resetFixture() {
        jdbc.update("DELETE FROM ai_usage_requests");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM ai_usage_control");
        jdbc.update("INSERT INTO ai_usage_control (id) VALUES (1)");
        clock.now = NOW;
    }

    protected UUID account(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, id.toString(), role);
        return id;
    }

    protected void authenticate(UUID id, String role) {
        var user = User.withUsername(id.toString()).password("synthetic").roles(role).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, user.getPassword(), user.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    protected static final class MutableClock extends Clock {
        protected volatile Instant now;

        protected MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
