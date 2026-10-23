package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    void acceptsOnlyNonBlankPasswordsWithinTheInclusiveBounds() {
        assertFalse(PasswordPolicy.accepts(null));
        assertFalse(PasswordPolicy.accepts("x".repeat(PasswordPolicy.MIN_LENGTH - 1)));
        assertFalse(PasswordPolicy.accepts(" ".repeat(PasswordPolicy.MIN_LENGTH)));
        assertTrue(PasswordPolicy.accepts("x".repeat(PasswordPolicy.MIN_LENGTH)));
        assertTrue(PasswordPolicy.accepts("x".repeat(PasswordPolicy.MAX_LENGTH)));
        assertFalse(PasswordPolicy.accepts("x".repeat(PasswordPolicy.MAX_LENGTH + 1)));
    }
}
