package com.company.incidentdesk.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.company.incidentdesk.infrastructure.neon.NeonDatabaseUrl;

/** Real HTTP promotion and role-session journey over an explicitly isolated migrated PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_DESK_TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PromotionJourneyHttpPostgresTest {
    private static final String REPORTER_LOGIN = "journey-reporter";
    private static final String REPORTER_PASSWORD = "synthetic-reporter-password";
    private static final String ADMIN_LOGIN = "journey-admin";
    private static final String ADMIN_PASSWORD = "synthetic-admin-password";
    private static final String CATEGORY = "IT";
    private static final Instant NOW = Instant.parse("2026-10-19T06:30:00Z");
    private static final String SCHEMA = "promotion_http_" + UUID.randomUUID().toString().replace("-", "");
    private static DatabaseTarget target;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureIsolatedDatabase(DynamicPropertyRegistry properties) {
        DatabaseTarget database = databaseTarget();
        properties.add("DATABASE_URL", database::applicationDatabaseUrl);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        if (target != null) {
            new JdbcTemplate(target.administrativeDataSource()).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void seedAdministratorAndClearTestState() {
        jdbc.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM responder_category_access");
        jdbc.update("DELETE FROM responder_promotion_requests");
        jdbc.update("DELETE FROM account_credentials");
        jdbc.update("DELETE FROM accounts");
        UUID administratorId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, login_name, role, status) VALUES (?, ?, 'ADMINISTRATOR', 'ENABLED')",
                administratorId, ADMIN_LOGIN);
        jdbc.update("INSERT INTO account_credentials (account_id, password_hash, temporary) VALUES (?, ?, FALSE)",
                administratorId, new BCryptPasswordEncoder(4).encode(ADMIN_PASSWORD));
    }

    @Test
    void reporterAdminAndFreshResponderSessionsCompleteSubsetPromotion() throws Exception {
        Browser reporter = new Browser();
        primeCsrf(reporter);
        HttpResponse<String> registered = postJson(reporter, "/api/v1/auth/reporter/register",
                "{\"loginName\":\"" + REPORTER_LOGIN + "\",\"password\":\"" + REPORTER_PASSWORD
                        + "\",\"passwordConfirmation\":\"" + REPORTER_PASSWORD + "\"}");
        assertEquals(201, registered.statusCode());
        assertEquals(204, login(reporter, "reporter", REPORTER_LOGIN, REPORTER_PASSWORD).statusCode());
        primeCsrf(reporter);

        Browser secondReporterSession = new Browser();
        primeCsrf(secondReporterSession);
        assertEquals(204, login(secondReporterSession, "reporter", REPORTER_LOGIN, REPORTER_PASSWORD).statusCode());
        primeCsrf(secondReporterSession);

        HttpResponse<String> submitted = postJson(reporter, "/api/v1/reporter/promotion-request",
                "{\"requestedCategories\":[\"IT\",\"FACILITIES\"],\"comments\":\"synthetic request\"}");
        assertEquals(201, submitted.statusCode());
        UUID requestId = UUID.fromString(jsonStringField(submitted.body(), "requestId"));

        Browser administrator = new Browser();
        primeCsrf(administrator);
        assertEquals(204, login(administrator, "administrator", ADMIN_LOGIN, ADMIN_PASSWORD).statusCode());
        HttpResponse<String> pending = get(administrator, "/api/v1/admin/promotion-requests");
        assertEquals(200, pending.statusCode());
        assertTrue(pending.body().contains(requestId.toString()));
        assertEquals(200, postJson(administrator,
                "/api/v1/admin/promotion-requests/" + requestId + "/decision",
                "{\"decision\":\"APPROVE\",\"approvedCategories\":[\"" + CATEGORY + "\"]}").statusCode());

        assertEquals("RESPONDER", jdbc.queryForObject(
                "SELECT role FROM accounts WHERE login_name = ?", String.class, REPORTER_LOGIN));
        assertEquals(List.of(CATEGORY), jdbc.queryForList("""
                SELECT category FROM responder_category_access
                 WHERE account_id = (SELECT id FROM accounts WHERE login_name = ?)
                 ORDER BY category
                """, String.class, REPORTER_LOGIN));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'RESPONDER_PROMOTION_APPROVED'",
                Integer.class));

        HttpResponse<String> staleReporterAccess = get(reporter, "/api/v1/reporter/promotion-request");
        assertEquals(401, staleReporterAccess.statusCode());
        HttpResponse<String> staleSecondReporterAccess = get(secondReporterSession,
                "/api/v1/reporter/promotion-request");
        assertEquals(401, staleSecondReporterAccess.statusCode());

        Browser wrongPortal = new Browser();
        primeCsrf(wrongPortal);
        HttpResponse<String> wrongRole = login(wrongPortal, "reporter", REPORTER_LOGIN, REPORTER_PASSWORD);
        assertEquals(401, wrongRole.statusCode());
        assertTrue(wrongRole.body().contains("AUTHENTICATION_FAILED"));

        Browser responder = new Browser();
        primeCsrf(responder);
        assertEquals(204, login(responder, "responder", REPORTER_LOGIN, REPORTER_PASSWORD).statusCode());
        String session = get(responder, "/api/v1/auth/session").body();
        assertTrue(session.contains("\"role\":\"RESPONDER\""));
        assertTrue(session.contains("\"responderCategories\":[\"" + CATEGORY + "\"]"));
    }

    private HttpResponse<String> login(Browser browser, String portal, String username, String password)
            throws IOException, InterruptedException {
        String form = "username=" + formValue(username) + "&password=" + formValue(password);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/" + portal + "/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", browser.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(Browser browser, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", browser.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Browser browser, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void primeCsrf(Browser browser) throws IOException, InterruptedException {
        HttpResponse<String> response = get(browser, "/api/v1/auth/session");
        assertEquals(200, response.statusCode());
        browser.csrfToken();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String formValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonStringField(String body, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(body);
        if (!matcher.find()) throw new AssertionError("JSON field not found: " + field);
        return matcher.group(1);
    }

    private static synchronized DatabaseTarget databaseTarget() {
        if (target != null) return target;
        if (!"true".equalsIgnoreCase(System.getenv("INCIDENT_DESK_TEST_DATABASE_CONFIRM_ISOLATED"))) {
            throw new IllegalStateException("An explicit isolated PostgreSQL test confirmation is required");
        }
        String testUrl = requiredEnvironment("INCIDENT_DESK_TEST_DATABASE_URL");
        NeonDatabaseUrl database = NeonDatabaseUrl.parse(testUrl);
        if (testUrl.toLowerCase(java.util.Locale.ROOT).contains("-pooler.")) {
            throw new IllegalStateException("Promotion journey tests require a direct PostgreSQL test endpoint");
        }
        ensureDifferentFromApplicationDatabase(database.jdbcUrl());
        DataSource admin = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + SCHEMA);
        String schemaUrl = withCurrentSchema(testUrl, SCHEMA);
        String schemaJdbcUrl = NeonDatabaseUrl.parse(schemaUrl).jdbcUrl();
        DataSource testDataSource = new DriverManagerDataSource(schemaJdbcUrl, database.username(), database.password());
        Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                .defaultSchema(SCHEMA).schemas(SCHEMA).load().migrate();
        target = new DatabaseTarget(admin, schemaUrl);
        return target;
    }

    private static void ensureDifferentFromApplicationDatabase(String isolatedJdbcUrl) {
        String isolatedTarget = databaseIdentity(isolatedJdbcUrl);
        for (String name : List.of("DATABASE_URL", "DATABASE_URL_UNPOOLED")) {
            String applicationUrl = System.getenv(name);
            if (applicationUrl != null && !applicationUrl.isBlank()
                    && isolatedTarget.equals(databaseIdentity(NeonDatabaseUrl.parse(applicationUrl).jdbcUrl()))) {
                throw new IllegalStateException("The isolated test database must differ from the application database");
            }
        }
    }

    private static String databaseIdentity(String jdbcUrl) {
        String normalized = jdbcUrl.substring("jdbc:".length());
        URI uri = URI.create(normalized);
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        return uri.getHost().toLowerCase(java.util.Locale.ROOT).replaceFirst("-pooler(?=\\.)", "")
                + ":" + port + uri.getPath();
    }

    private static String withCurrentSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();

        private HttpClient client() { return client; }

        private String csrfToken() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("CSRF cookie was not issued"));
        }
    }

    private record DatabaseTarget(DataSource administrativeDataSource, String applicationDatabaseUrl) { }
}
