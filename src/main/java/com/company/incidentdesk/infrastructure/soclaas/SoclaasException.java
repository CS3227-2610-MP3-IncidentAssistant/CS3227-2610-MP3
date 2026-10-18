package com.company.incidentdesk.infrastructure.soclaas;

/** Safe provider outcome, deliberately excluding upstream exceptions and bodies. */
public final class SoclaasException extends RuntimeException {
    /** Stable categories understood by the later gateway integration. */
    public enum Category {
        AI_RATE_LIMITED, AI_TIMEOUT, AI_UNAVAILABLE, AI_REJECTED, AI_INVALID_RESPONSE, AI_OUTPUT_TOO_LARGE
    }

    private final Category category;
    private final int providerStatus;
    private final Long retryAfterSeconds;

    SoclaasException(Category category) {
        this(category, 0, null);
    }

    SoclaasException(Category category, int providerStatus, Long retryAfterSeconds) {
        super(category.name(), null, false, false);
        this.category = category;
        this.providerStatus = providerStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Category category() {
        return category;
    }

    /** Internal numeric hint for shared circuits; zero means no provider status was received. */
    public int providerStatus() {
        return providerStatus;
    }

    /** Parsed retry delay, or null when absent, invalid, or outside the bounded one-day range. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
