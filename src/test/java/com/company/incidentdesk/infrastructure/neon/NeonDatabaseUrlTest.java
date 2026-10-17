package com.company.incidentdesk.infrastructure.neon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NeonDatabaseUrlTest {
    @Test
    void convertsNeonUriToJdbcSettingsWithoutLeavingCredentialsInJdbcUrl() {
        NeonDatabaseUrl result = NeonDatabaseUrl.parse(
                "postgresql://app%40user:p%3Ass+word@ep-example-pooler.ap-southeast-1.aws.neon.tech/neondb"
                        + "?sslmode=require&channel_binding=require");

        assertEquals("app@user", result.username());
        assertEquals("p:ss+word", result.password());
        assertEquals(
                "jdbc:postgresql://ep-example-pooler.ap-southeast-1.aws.neon.tech:5432/neondb"
                        + "?sslmode=require&channel_binding=require",
                result.jdbcUrl());
    }

    @Test
    void rejectsIncompleteOrNonPostgresUrls() {
        assertThrows(IllegalArgumentException.class, () -> NeonDatabaseUrl.parse("https://example.com/db"));
        assertThrows(IllegalArgumentException.class, () -> NeonDatabaseUrl.parse("postgresql://example.com/db"));
        assertThrows(IllegalArgumentException.class, () -> NeonDatabaseUrl.parse("not a URI"));
    }
}
