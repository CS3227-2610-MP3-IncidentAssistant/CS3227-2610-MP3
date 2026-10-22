package com.company.incidentdesk.infrastructure.soclaas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;

@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED", matches = "(?i:true)")
class AiMigrationUpgradePostgresTest {
    @Test
    void canonicalPromotionSchemaUpgradesToAiWithoutLosingExistingAccounts() {
        assertEquals("true", System.getenv("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"),
                "Explicit isolated database confirmation is required");
        var database = NeonDatabaseUrl.parse(System.getenv("INCIDENT_DESK_TEST_DATABASE_URL"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        String schema = "ai_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            String url = database.jdbcUrl() + (database.jdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var source = new DriverManagerDataSource(url, database.username(), database.password());
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).target("4").load().migrate();
            UUID reporter = UUID.randomUUID();
            jdbc.update("INSERT INTO accounts(id,login_name,role,status) VALUES (?,?,'REPORTER','ENABLED')",
                    reporter, reporter.toString());
            UUID promotion = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO responder_promotion_requests(id,requester_id,requested_at,status)
                    VALUES (?,?,?,'PENDING')
                    """, promotion, reporter, java.sql.Timestamp.from(java.time.Instant.parse("2026-10-19T09:00:00Z")));
            var latest = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load();
            latest.migrate();
            latest.validate();
            assertEquals("REPORTER", jdbc.queryForObject("SELECT role FROM accounts WHERE id=?", String.class, reporter));
            assertEquals(reporter.toString(), jdbc.queryForObject("SELECT login_name FROM accounts WHERE id=?", String.class, reporter));
            assertEquals("ENABLED", jdbc.queryForObject("SELECT status FROM accounts WHERE id=?", String.class, reporter));
            assertEquals(reporter, jdbc.queryForObject("SELECT requester_id FROM responder_promotion_requests WHERE id=?", UUID.class, promotion));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM responder_promotion_requests WHERE id=?", String.class, promotion));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_control", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_resolutions", Integer.class));
            for (String table : new String[] {"responder_promotion_requested_categories",
                    "responder_promotion_approved_categories", "responder_category_access"}) {
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            }
            assertEquals(9, jdbc.queryForObject("SELECT MAX(CAST(version AS integer)) FROM flyway_schema_history WHERE success", Integer.class));
            assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success", Integer.class));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
