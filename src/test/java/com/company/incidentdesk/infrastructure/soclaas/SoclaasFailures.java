package com.company.incidentdesk.infrastructure.soclaas;

/**
 * Test-only factory for the safe provider outcome categories. It exists so resilience tests outside
 * this package can simulate each failure without widening the production constructor visibility.
 */
public final class SoclaasFailures {
    private SoclaasFailures() { }

    public static SoclaasException of(SoclaasException.Category category) {
        return new SoclaasException(category);
    }
}
