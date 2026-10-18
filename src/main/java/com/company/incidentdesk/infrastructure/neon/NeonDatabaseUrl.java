package com.company.incidentdesk.infrastructure.neon;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Converts a Neon PostgreSQL URI into explicit JDBC connection settings. */
public record NeonDatabaseUrl(String jdbcUrl, String username, String password) {
    public static NeonDatabaseUrl parse(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid URI", exception);
        }
        if (!("postgres".equals(uri.getScheme()) || "postgresql".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() == null || uri.getPath().length() <= 1) {
            throw new IllegalArgumentException("DATABASE_URL must be a complete PostgreSQL URI");
        }

        String[] credentials = uri.getRawUserInfo().split(":", 2);
        if (credentials.length != 2) {
            throw new IllegalArgumentException("DATABASE_URL must include a username and password");
        }
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query;
        return new NeonDatabaseUrl(
                jdbcUrl,
                decodeUserInfo(credentials[0]),
                decodeUserInfo(credentials[1]));
    }

    private static String decodeUserInfo(String value) {
        // URLDecoder implements HTML form rules where '+' means a space. In a
        // URI user-info component '+' is literal, so protect it before percent decoding.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
