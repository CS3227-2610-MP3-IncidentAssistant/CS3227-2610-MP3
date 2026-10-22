package com.company.incidentdesk.web.admin;

import java.util.Objects;
import java.util.Optional;

/**
 * Stable outcome of the security-sensitive temporary-password reset transaction.
 *
 * <p>On success the generated plaintext is carried only in memory and can be consumed exactly
 * once, so it can be revealed to the acting Administrator without ever being stored or logged.
 */
final class TemporaryPasswordResetOutcome {
    enum Status {
        RESET,
        RESOURCE_UNAVAILABLE,
        INVALID_STATE,
        CONFLICT,
        PERSISTENCE_FAILURE
    }

    private final Status status;
    private String temporaryPassword;

    private TemporaryPasswordResetOutcome(Status status, String temporaryPassword) {
        this.status = status;
        this.temporaryPassword = temporaryPassword;
    }

    static TemporaryPasswordResetOutcome reset(String temporaryPassword) {
        return new TemporaryPasswordResetOutcome(
                Status.RESET, Objects.requireNonNull(temporaryPassword, "temporaryPassword"));
    }

    static TemporaryPasswordResetOutcome failure(Status status) {
        if (Objects.requireNonNull(status, "status") == Status.RESET) {
            throw new IllegalArgumentException("a successful reset requires a temporary password");
        }
        return new TemporaryPasswordResetOutcome(status, null);
    }

    Status status() {
        return status;
    }

    /** Returns the plaintext at most once; later calls see an empty value. */
    synchronized Optional<String> takeTemporaryPassword() {
        Optional<String> revealed = Optional.ofNullable(temporaryPassword);
        temporaryPassword = null;
        return revealed;
    }

    /** Never renders the secret, so accidental logging cannot disclose it. */
    @Override
    public String toString() {
        return "TemporaryPasswordResetOutcome[status=" + status + "]";
    }
}
