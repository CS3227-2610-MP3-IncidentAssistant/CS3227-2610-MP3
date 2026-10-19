package com.company.incidentdesk.infrastructure.soclaas;

/** A presentation-safe admission failure; database causes and SQL values are discarded. */
public final class AiUsageException extends RuntimeException {
    public enum Category { AI_RATE_LIMITED, AI_BUDGET_EXHAUSTED, AI_UNAVAILABLE, AI_REJECTED }

    private final Category category;

    public AiUsageException(Category category) {
        super(category.name(), null, false, false);
        this.category = category;
    }

    public Category category() { return category; }
}
