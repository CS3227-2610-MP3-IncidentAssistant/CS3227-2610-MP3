package com.company.incidentdesk.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;

/**
 * Creates and seeds an isolated PostgreSQL schema for the Playwright browser
 * suite (administrator slice required verification 5 and 7).
 *
 * <p>This lives under {@code src/test/java} on purpose: the application must not
 * ship a seeding endpoint or any test-only route. The browser harness therefore
 * seeds out of band through JDBC, exactly as the existing PostgreSQL integration
 * suites do, and the running application keeps its production security wiring.
 *
 * <p>Usage: {@code create <schema>} then {@code drop <schema>}. The seeder
 * refuses to run unless {@code INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED} is
 * {@code true}, so it cannot be aimed at production data.
 *
 * <p>The passwords here are fixed, non-secret fixture credentials for a
 * throwaway schema. They are never valid against any deployed environment.
 */
public final class BrowserTestDataSeeder {
    /** Fixture credentials for the browser suite only. */
    public static final String ADMINISTRATOR_LOGIN = "e2e-administrator";
    public static final String RESPONDER_LOGIN = "e2e-responder";
    public static final String REPORTER_LOGIN = "e2e-reporter";
    public static final String SECOND_ADMINISTRATOR_LOGIN = "e2e-administrator-two";
    public static final String TEMPORARY_CREDENTIAL_LOGIN = "e2e-temporary";
    /** A reporter holding a pending promotion request, for the decision paths. */
    public static final String PROMOTION_CANDIDATE_LOGIN = "e2e-promotion-candidate";
    public static final String PASSWORD = "E2eFixturePassword!1";

    /** A deterministic clock origin so seeded SLO and statistics values are stable. */
    private static final Instant SEED_NOW = Instant.parse("2026-10-20T09:00:00Z");

    private BrowserTestDataSeeder() { }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            System.err.println("Usage: BrowserTestDataSeeder <create|drop> <schema>");
            System.exit(2);
            return;
        }
        requireIsolationConfirmation();
        String command = arguments[0];
        String schema = requireSafeSchemaName(arguments[1]);
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(
                requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL"));
        DataSource administrative = PostgresTestDataSource.administrative(
                database.jdbcUrl(), database.username(), database.password());

        switch (command) {
            case "create" -> create(database, administrative, schema);
            case "drop" -> drop(administrative, schema);
            default -> {
                System.err.println("Unknown command: " + command);
                System.exit(2);
            }
        }
    }

    private static void create(NeonDatabaseUrl database, DataSource administrative, String schema) {
        JdbcTemplate administrativeJdbc = new JdbcTemplate(administrative);
        administrativeJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        administrativeJdbc.execute("CREATE SCHEMA " + schema);
        DataSource isolated = PostgresTestDataSource.isolated(
                database.jdbcUrl(), database.username(), database.password(), schema);
        Flyway.configure().dataSource(isolated).locations("classpath:db/migration")
                .defaultSchema(schema).schemas(schema).load().migrate();
        seed(new JdbcTemplate(isolated));

        // The application builds its own pool from DATABASE_URL and does not
        // accept a schema setting, and this provider ignores the driver's
        // `currentSchema` parameter, so the isolated schema is pinned to the
        // role instead. Every later connection then resolves to it, including
        // the application's own Flyway run. This only ever targets a test role
        // on an explicitly confirmed isolated database.
        administrativeJdbc.execute("ALTER ROLE CURRENT_USER SET search_path TO " + schema);
    }

    /** Removes the schema and the role-level search_path pinned by {@code create}. */
    private static void drop(DataSource administrative, String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(administrative);
        jdbc.execute("ALTER ROLE CURRENT_USER RESET search_path");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static void seed(JdbcTemplate jdbc) {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        UUID administrator = account(jdbc, ADMINISTRATOR_LOGIN, "ADMINISTRATOR", hash, false);
        account(jdbc, SECOND_ADMINISTRATOR_LOGIN, "ADMINISTRATOR", hash, false);
        UUID responder = account(jdbc, RESPONDER_LOGIN, "RESPONDER", hash, false);
        UUID reporter = account(jdbc, REPORTER_LOGIN, "REPORTER", hash, false);
        // Exercises the forced password-change path in a real browser.
        account(jdbc, TEMPORARY_CREDENTIAL_LOGIN, "REPORTER", hash, true);

        jdbc.update("""
                INSERT INTO responder_category_access (account_id, category, granted_at)
                VALUES (?, 'IT', ?)
                """, responder, Timestamp.from(SEED_NOW));

        // Enough incidents to make server-side pagination, search, filtering, and
        // sorting observable, with a stable tie-breaker pair at identical times.
        for (int index = 0; index < 30; index++) {
            incident(jdbc, reporter, responder, IncidentSeed.forIndex(index));
        }
        // A deliberate created_at collision so the dashboard id tie-breaker shows.
        incident(jdbc, reporter, responder, new IncidentSeed(
                "Tie-breaker duplicate timestamp", "Shares created_at with its pair.",
                "IT", "SUBMITTED", false, SEED_NOW.minus(1, ChronoUnit.HOURS)));
        incident(jdbc, reporter, responder, new IncidentSeed(
                "Tie-breaker duplicate timestamp", "Shares created_at with its pair.",
                "IT", "SUBMITTED", false, SEED_NOW.minus(1, ChronoUnit.HOURS)));
        // An anonymous incident so privacy-safe identity rendering is verifiable.
        incident(jdbc, reporter, responder, new IncidentSeed(
                "Anonymous facilities concern", "Submitted anonymously by a reporter.",
                "FACILITIES", "SUBMITTED", true, SEED_NOW.minus(3, ChronoUnit.HOURS)));

        seedSloVersions(jdbc, administrator);
        seedPendingPromotionRequest(jdbc, hash);
    }

    /**
     * One pending promotion request so the approve and reject decision paths are
     * observable in the browser. Only one pending request per reporter is
     * permitted, so this candidate exists solely for that queue.
     */
    private static void seedPendingPromotionRequest(JdbcTemplate jdbc, String passwordHash) {
        UUID candidate = account(jdbc, PROMOTION_CANDIDATE_LOGIN, "REPORTER", passwordHash, false);
        UUID request = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO responder_promotion_requests (id, requester_id, comments, requested_at, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, request, candidate,
                "Requesting responder access for browser verification.",
                Timestamp.from(SEED_NOW.minus(2, ChronoUnit.DAYS)));
        for (String category : List.of("IT", "FACILITIES")) {
            jdbc.update("""
                    INSERT INTO responder_promotion_requested_categories (request_id, category)
                    VALUES (?, ?)
                    """, request, category);
        }
    }

    private static void seedSloVersions(JdbcTemplate jdbc, UUID administrator) {
        for (String category : List.of("IT", "FACILITIES", "HUMAN_RELATIONS")) {
            jdbc.update("""
                    INSERT INTO slo_target_versions (id, category, average_time_to_claim_minutes,
                        average_time_in_progress_minutes, average_reopen_rate_percent,
                        effective_at, changed_by)
                    VALUES (?, ?, 60, 480, 5.00, ?, ?)
                    """, UUID.randomUUID(), category,
                    Timestamp.from(SEED_NOW.minus(30, ChronoUnit.DAYS)), administrator);
        }
    }

    private record IncidentSeed(String title, String description, String category,
            String status, boolean anonymous, Instant createdAt) {

        private static final String[] CATEGORIES = { "IT", "FACILITIES", "HUMAN_RELATIONS" };
        private static final String[] STATUSES = { "SUBMITTED", "SUBMITTED", "ASSIGNED", "RESOLVED" };

        static IncidentSeed forIndex(int index) {
            return new IncidentSeed(
                    "Seeded incident " + (index + 1),
                    "Deterministic seeded description for browser verification number " + (index + 1) + ".",
                    CATEGORIES[index % CATEGORIES.length],
                    STATUSES[index % STATUSES.length],
                    false,
                    SEED_NOW.minus(index + 4L, ChronoUnit.HOURS));
        }
    }

    private static void incident(JdbcTemplate jdbc, UUID reporter, UUID responder, IncidentSeed seed) {
        boolean assigned = "ASSIGNED".equals(seed.status());
        Instant submittedAt = seed.createdAt().plus(1, ChronoUnit.MINUTES);
        Timestamp assignedAt = assigned
                ? Timestamp.from(submittedAt.plus(20, ChronoUnit.MINUTES))
                : null;
        jdbc.update("""
                INSERT INTO incidents (id, reporter_id, title, description, category, status, anonymous,
                    created_at, submitted_at, queue_entered_at, first_assigned_at, latest_assigned_at,
                    assignee_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), reporter, seed.title(), seed.description(), seed.category(),
                seed.status(), seed.anonymous(),
                Timestamp.from(seed.createdAt()), Timestamp.from(submittedAt),
                Timestamp.from(submittedAt), assignedAt, assignedAt,
                assigned ? responder : null);
    }

    private static UUID account(JdbcTemplate jdbc, String loginName, String role,
            String passwordHash, boolean temporary) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, ?, 'ENABLED')",
                id, loginName, role);
        jdbc.update("""
                INSERT INTO account_credentials (account_id, password_hash, temporary)
                VALUES (?, ?, ?)
                """, id, passwordHash, temporary);
        return id;
    }

    private static void requireIsolationConfirmation() {
        String confirmation = System.getenv("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED");
        if (confirmation == null || !confirmation.equalsIgnoreCase("true")) {
            throw new IllegalStateException(
                    "Refusing to seed: set INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED=true to confirm "
                            + "the target is an isolated test database.");
        }
    }

    private static String requireSafeSchemaName(String schema) {
        if (!schema.toLowerCase(Locale.ROOT).matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Unsafe schema name: " + schema);
        }
        return schema;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
