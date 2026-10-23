package com.company.incidentdesk.web.security;

/** Shared bounds for user-selected permanent passwords. */
final class PasswordPolicy {
    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    static boolean accepts(String password) {
        return password != null && password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH && !password.isBlank();
    }
}
