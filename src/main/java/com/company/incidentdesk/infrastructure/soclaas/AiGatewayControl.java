package com.company.incidentdesk.infrastructure.soclaas;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.soclaas.AiUsageException.Category;
import com.company.incidentdesk.infrastructure.soclaas.AiUsageLedger.Feature;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;

/** Atomic gateway admission/audit and shared circuit state; provider I/O stays outside these transactions. */
final class AiGatewayControl {
    record Ticket(UUID id, Instant leaseUntil) { }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final AiUsagePolicy.Limits limits;

    AiGatewayControl(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, AiUsagePolicy.Limits limits) {
        this.jdbc = jdbc;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(4);
        this.limits = limits;
    }

    UUID claimRefresh(long revision) {
        if (revision < 1) { throw unavailable(); }
        return locked(state -> {
            if (revision < state.revision || (state.refreshUntil != null && state.refreshUntil.isAfter(state.now))) {
                return null;
            }
            if (revision == state.revision) {
                if (state.kind.equals("BLOCKED") || (state.until != null && state.until.isAfter(state.now))) { return null; }
                if (state.verified != null && !state.now.isBefore(state.verified)
                        && state.now.isBefore(state.verified.plusSeconds(300))
                        && state.now.truncatedTo(ChronoUnit.DAYS).equals(state.verified.truncatedTo(ChronoUnit.DAYS))) {
                    return null;
                }
            }
            UUID owner = UUID.randomUUID();
            jdbc.update("UPDATE ai_usage_control SET refresh_owner=?, refresh_revision=?, refresh_until=? WHERE id=1",
                    owner, revision, stamp(state.now.plusSeconds(60)));
            return owner;
        });
    }

    void publish(UUID owner, long revision, Snapshot snapshot) {
        locked(state -> {
            requireRefreshOwner(state, owner);
            if (revision < state.revision || revision != state.refreshRevision) { throw unavailable(); }
            ledger(state.now).refresh(snapshot);
            if (revision > state.revision) {
                jdbc.update("""
                        UPDATE ai_usage_control SET configuration_revision=?, circuit_kind='CLOSED', circuit_until=NULL,
                            probe_until=NULL, probe_owner=NULL, transient_failures=0, circuit_generation=circuit_generation+1 WHERE id=1
                        """, revision);
            }
            jdbc.update("UPDATE ai_usage_control SET refresh_owner=NULL, refresh_revision=NULL, refresh_until=NULL WHERE id=1");
            return true;
        });
    }

    void failRefresh(UUID owner, SoclaasException failure) {
        locked(state -> {
            requireRefreshOwner(state, owner);
            jdbc.update("""
                    UPDATE ai_usage_control SET verified_at=NULL, configuration_revision=GREATEST(configuration_revision, ?),
                        refresh_owner=NULL, refresh_revision=NULL, refresh_until=NULL WHERE id=1
                    """, state.refreshRevision);
            openForFailure(state, failure, true);
            return true;
        });
    }

    Ticket admit(UUID actor, Feature feature, String model, int input, int output, long revision) {
        return locked(state -> {
            recoverWithinLock(state.now);
            state = readState(state.now, false);
            if (revision != state.revision || state.kind.equals("BLOCKED")) { throw unavailable(); }
            boolean probe = !state.kind.equals("CLOSED");
            if (probe && (state.until.isAfter(state.now)
                    || (state.probeUntil != null && state.probeUntil.isAfter(state.now)))) {
                throw new AiUsageException(state.kind.equals("LIMITED") ? Category.AI_RATE_LIMITED : Category.AI_UNAVAILABLE);
            }
            String role = role(feature);
            var enabled = jdbc.query("SELECT role FROM accounts WHERE id=? AND status='ENABLED' FOR SHARE",
                    (row, index) -> row.getString(1), actor);
            if (enabled.isEmpty() || !role.equals(enabled.getFirst())) { throw new AiUsageException(Category.AI_REJECTED); }
            var reservation = ledger(state.now).admit(actor, feature, model, input, output);
            jdbc.update("UPDATE ai_usage_requests SET actor_role=?, gateway_revision=?, gateway_generation=? WHERE id=?",
                    role, revision, state.generation, reservation.id());
            audit(reservation.id(), actor, role, feature.name(), "AI_REQUEST_ACCEPTED", "ACCEPTED", state.now);
            if (probe) {
                jdbc.update("UPDATE ai_usage_control SET probe_until=?, probe_owner=? WHERE id=1",
                        stamp(reservation.leaseUntil()), reservation.id());
            }
            return new Ticket(reservation.id(), reservation.leaseUntil());
        });
    }

    /** Null means successful delivery. Replayed settlements do not change circuit state or accounting. */
    SoclaasException.Category finish(Ticket ticket, SoclaasClient.Usage usage, SoclaasException failure) {
        return locked(state -> {
            recoverWithinLock(state.now);
            state = readState(state.now, false);
            var requests = jdbc.query("""
                    SELECT account_id, actor_role, feature, gateway_revision, gateway_generation, outcome, gateway_outcome
                    FROM ai_usage_requests WHERE id=? AND gateway_revision IS NOT NULL
                    """, (row, index) -> new Pending(row.getObject("account_id", UUID.class), row.getString("actor_role"),
                            row.getString("feature"), row.getLong("gateway_revision"), row.getLong("gateway_generation"),
                            row.getString("outcome"), row.getString("gateway_outcome")), ticket.id());
            if (requests.isEmpty()) { throw unavailable(); }
            Pending request = requests.getFirst();
            if (!request.outcome.equals("IN_FLIGHT")) {
                return request.category == null || request.category.equals("SUCCESS") ? null
                        : SoclaasException.Category.valueOf(request.category);
            }
            ledger(state.now).finish(ticket.id(), failure == null ? usage : null);
            String outcome = failure == null ? "SUCCESS" : failure.category().name();
            String terminal = jdbc.queryForObject("SELECT outcome FROM ai_usage_requests WHERE id=?", String.class, ticket.id());
            if (terminal.equals("EXPIRED")) { outcome = "AI_TIMEOUT"; }
            audit(ticket.id(), request.actor, request.role, request.feature, "AI_REQUEST_FINISHED", outcome, state.now);
            jdbc.update("UPDATE ai_usage_requests SET gateway_outcome=?, gateway_outcome_audited=TRUE WHERE id=?",
                    outcome, ticket.id());
            if (request.revision == state.revision) {
                clearProbe(ticket.id());
                if (outcome.equals("AI_TIMEOUT")) { openForFailure(state, new SoclaasException(SoclaasException.Category.AI_TIMEOUT), false); }
                else if (failure != null) { openForFailure(state, failure, false); }
                else if (request.generation == state.generation && outcome.equals("SUCCESS")) {
                    jdbc.update("""
                            UPDATE ai_usage_control SET circuit_kind='CLOSED', circuit_until=NULL,
                                probe_until=NULL, probe_owner=NULL, transient_failures=0 WHERE id=1
                            """);
                }
            }
            return outcome.equals("SUCCESS") ? null : SoclaasException.Category.valueOf(outcome);
        });
    }

    int recover() { return locked(state -> recoverWithinLock(state.now)); }

    private int recoverWithinLock(Instant now) {
        var expired = jdbc.query("""
                SELECT id, account_id, actor_role, feature, lease_until, gateway_revision FROM ai_usage_requests
                WHERE gateway_revision IS NOT NULL AND NOT gateway_outcome_audited
                    AND (outcome='EXPIRED' OR (outcome='IN_FLIGHT' AND lease_until <= ?))
                """, (row, index) -> new Expired(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
                        row.getString("actor_role"), row.getString("feature"), row.getTimestamp("lease_until").toInstant(),
                        row.getLong("gateway_revision")), stamp(now));
        for (Expired request : expired) {
            jdbc.update("""
                    UPDATE ai_usage_requests SET outcome='EXPIRED', finished_at=?, gateway_outcome='AI_TIMEOUT',
                        gateway_outcome_audited=TRUE WHERE id=?
                    """, stamp(request.until), request.id);
            audit(request.id, request.actor, request.role, request.feature, "AI_REQUEST_FINISHED", "AI_TIMEOUT", request.until);
            State state = readState(now, false);
            if (request.revision == state.revision) {
                clearProbe(request.id);
                openForFailure(state, new SoclaasException(SoclaasException.Category.AI_TIMEOUT), false);
            }
        }
        ledger(now).recoverExpired();
        return expired.size();
    }

    private void openForFailure(State state, SoclaasException failure, boolean metadata) {
        int status = failure.providerStatus();
        if (status == 401 || status == 403) { open("BLOCKED", null, 0); return; }
        if (status == 429) {
            Long hint = failure.retryAfterSeconds();
            long seconds = hint != null && hint > 0 && hint <= 86400 ? hint : 300;
            // An in-flight older failure must never downgrade an indefinite block.
            if (!state.kind.equals("BLOCKED")) {
                Instant until = state.now.plusSeconds(seconds);
                if (state.kind.equals("LIMITED") && state.until.isAfter(until)) { until = state.until; }
                open("LIMITED", until, 0);
            }
            return;
        }
        boolean transientFailure = status >= 500 || (status == 0
                && (failure.category() == SoclaasException.Category.AI_TIMEOUT
                    || failure.category() == SoclaasException.Category.AI_UNAVAILABLE));
        if ((transientFailure || metadata) && !state.kind.equals("BLOCKED")
                && !(state.kind.equals("LIMITED") && state.until.isAfter(state.now))) {
            int failures = Math.min(3, state.failures + 1);
            if (metadata || failures == 3 || !state.kind.equals("CLOSED")) {
                open("TRANSIENT", state.now.plusSeconds(30), failures);
            } else {
                jdbc.update("UPDATE ai_usage_control SET transient_failures=?, circuit_generation=circuit_generation+1 WHERE id=1",
                        failures);
            }
        }
    }

    private void open(String kind, Instant until, int failures) {
        jdbc.update("""
                UPDATE ai_usage_control SET circuit_kind=?, circuit_until=?, transient_failures=?,
                    circuit_generation=circuit_generation+1 WHERE id=1
                """, kind, until == null ? null : stamp(until), failures);
    }

    private void clearProbe(UUID owner) {
        jdbc.update("UPDATE ai_usage_control SET probe_owner=NULL, probe_until=NULL WHERE id=1 AND probe_owner=?", owner);
    }

    private AiUsageLedger ledger(Instant now) {
        // Admission, recovery and audit share one transaction instant, including nested ledger work.
        return AiUsageLedger.participating(jdbc, transactions.getTransactionManager(), Clock.fixed(now, ZoneOffset.UTC), limits);
    }

    private void audit(UUID request, UUID actor, String role, String feature, String action, String outcome, Instant now) {
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, actor_account_id, actor_role, action,
                    target_type, target_identifier, outcome, details)
                VALUES (?, ?, ?, ?, ?, 'AI_REQUEST', ?, ?, jsonb_build_object('feature', CAST(? AS text),
                    'category', CAST(? AS text)))
                """, UUID.randomUUID(), stamp(now), actor, role, action, request.toString(),
                outcome.equals("ACCEPTED") || outcome.equals("SUCCESS") ? "SUCCESS" : "FAILURE", feature, outcome);
    }

    private <T> T locked(Function<State, T> operation) {
        try {
            return transactions.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL statement_timeout='3s'");
                State state = readState(null, true);
                jdbc.update("UPDATE ai_usage_control SET last_seen=? WHERE id=1", stamp(state.now));
                return operation.apply(state);
            });
        } catch (AiUsageException safe) { throw safe; }
        catch (RuntimeException unsafe) { throw unavailable(); }
    }

    private State readState(Instant now, boolean lock) {
        return jdbc.queryForObject("SELECT * FROM ai_usage_control WHERE id=1" + (lock ? " FOR UPDATE" : ""), (row, index) -> {
            // The row is locked before sampling time, so lock wait/reordering cannot look like clock rollback.
            Instant captured = lock ? clock.instant().truncatedTo(ChronoUnit.MICROS) : now;
            Timestamp last = row.getTimestamp("last_seen");
            if (last != null && captured.isBefore(last.toInstant())) { throw unavailable(); }
            return new State(captured, row.getLong("configuration_revision"), row.getString("circuit_kind"),
                    instant(row.getTimestamp("circuit_until")), row.getLong("circuit_generation"),
                    row.getInt("transient_failures"), instant(row.getTimestamp("probe_until")),
                    row.getObject("refresh_owner", UUID.class), row.getLong("refresh_revision"), instant(row.getTimestamp("refresh_until")),
                    instant(row.getTimestamp("verified_at")));
        });
    }

    static String role(Feature feature) {
        return switch (feature) {
        case REPORTER_DESCRIPTION -> "REPORTER";
        case RESPONDER_INCIDENT -> "RESPONDER";
        case ADMINISTRATOR_TRENDS, ADMINISTRATOR_INSIGHTS -> "ADMINISTRATOR";
        };
    }

    private static void requireRefreshOwner(State state, UUID owner) {
        if (owner == null || !owner.equals(state.refreshOwner) || !state.refreshUntil.isAfter(state.now)) { throw unavailable(); }
    }

    private static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
    private static Instant instant(Timestamp stamp) { return stamp == null ? null : stamp.toInstant(); }
    private static AiUsageException unavailable() { return new AiUsageException(Category.AI_UNAVAILABLE); }
    private record State(Instant now, long revision, String kind, Instant until, long generation, int failures,
            Instant probeUntil, UUID refreshOwner, long refreshRevision, Instant refreshUntil, Instant verified) { }
    private record Pending(UUID actor, String role, String feature, long revision, long generation, String outcome, String category) { }
    private record Expired(UUID id, UUID actor, String role, String feature, Instant until, long revision) { }
}
