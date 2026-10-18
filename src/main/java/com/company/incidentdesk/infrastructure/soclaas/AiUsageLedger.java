package com.company.incidentdesk.infrastructure.soclaas;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.infrastructure.soclaas.AiUsageException.Category;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Limits;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Price;
import com.company.incidentdesk.infrastructure.soclaas.AiUsagePolicy.Snapshot;

/** Shared admission component. Callers own authorization, immediate dispatch, cancellation and audit. */
public final class AiUsageLedger {
    public enum Feature { REPORTER_DESCRIPTION, RESPONDER_INCIDENT, ADMINISTRATOR_TRENDS }
    public record Reservation(UUID id, Instant leaseUntil) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Limits configured;
    private final Supplier<UUID> identifiers;

    public AiUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, Limits configured) {
        this(jdbc, manager, clock, configured, UUID::randomUUID);
    }

    AiUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, Limits configured,
            Supplier<UUID> identifiers) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.configured = Objects.requireNonNull(configured);
        this.identifiers = Objects.requireNonNull(identifiers);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(4);
    }

    /** Only trusted backend verification may publish a snapshot; never bind this record from HTTP input. */
    public void refresh(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        locked(state -> {
            if (!snapshot.freshAt(state.now) || (state.snapshot != null
                    && snapshot.verifiedAt().isBefore(state.snapshot.verifiedAt()))) {
                throw new AiUsageException(Category.AI_UNAVAILABLE);
            }
            Snapshot previous = state.snapshot;
            long daySpent = snapshot.daySpent();
            long monthSpent = snapshot.monthSpent();
            long lifetimeSpent = snapshot.lifetimeSpent();
            if (previous != null) {
                if (utcDay(previous.verifiedAt()).equals(utcDay(snapshot.verifiedAt()))) {
                    daySpent = Math.max(daySpent, previous.daySpent());
                }
                if (utcMonth(previous.verifiedAt()).equals(utcMonth(snapshot.verifiedAt()))) {
                    monthSpent = Math.max(monthSpent, previous.monthSpent());
                }
                lifetimeSpent = Math.max(lifetimeSpent, previous.lifetimeSpent());
            }
            jdbc.update("""
                    UPDATE ai_usage_control SET verified_at = ?, provider_minute = ?, daily_allowance = ?,
                        monthly_allowance = ?, lifetime_allowance = ?, day_spent = ?, month_spent = ?,
                        lifetime_spent = ?, model = ?, input_price = ?, output_price = ? WHERE id = 1
                    """, timestamp(snapshot.verifiedAt()), snapshot.requestsPerMinute(), snapshot.daily(),
                    snapshot.monthly(), snapshot.lifetime(), daySpent, monthSpent, lifetimeSpent,
                    snapshot.price().model(), snapshot.price().inputPerMillion(), snapshot.price().outputPerMillion());
            return true;
        });
    }

    /** Input is a server-computed upper bound including instructions/framing, never a client token count. */
    public Reservation admit(UUID account, Feature feature, String expectedModel, int inputBound, int outputBound) {
        if (account == null || feature == null || inputBound < 1 || inputBound > 8000
                || outputBound < 1 || outputBound > 1200) {
            throw new AiUsageException(Category.AI_REJECTED);
        }
        Decision decision = locked(state -> {
            expire(state.now);
            if (state.snapshot == null || !state.snapshot.freshAt(state.now)
                    || !state.snapshot.price().model().equals(expectedModel)) {
                return new Decision(null, Category.AI_UNAVAILABLE);
            }
            Snapshot snapshot = state.snapshot;
            Limits limits = state.limits;
            if (count("outcome = 'IN_FLIGHT' AND account_id = ?", account) >= 1
                    || count("outcome = 'IN_FLIGHT'") >= limits.concurrent()
                    || count("account_id = ? AND admitted_at > ?", account, timestamp(state.now.minusSeconds(60)))
                            >= limits.accountMinute()
                    || count("account_id = ? AND admitted_at > ?", account, timestamp(state.now.minusSeconds(3600)))
                            >= limits.accountHour()
                    || count("account_id = ? AND admitted_at >= ?", account, timestamp(utcDay(state.now)))
                            >= limits.accountDay()
                    || count("admitted_at > ?", timestamp(state.now.minusSeconds(60)))
                            >= Math.min(limits.globalMinute(), snapshot.requestCeiling())) {
                return new Decision(null, Category.AI_RATE_LIMITED);
            }
            long reserved = snapshot.price().cost(inputBound, outputBound);
            if (exceeds(spendSince(utcDay(state.now)), snapshot.daySpent(), reserved,
                            Math.min(limits.daily(), AiUsagePolicy.reserveCeiling(snapshot.daily())))
                    || exceeds(spendSince(utcMonth(state.now)), snapshot.monthSpent(), reserved,
                            Math.min(limits.monthly(), AiUsagePolicy.reserveCeiling(snapshot.monthly())))
                    || exceeds(totalSpend(), snapshot.lifetimeSpent(), reserved,
                            snapshot.lifetime() == null ? limits.lifetime()
                                    : Math.min(limits.lifetime(), AiUsagePolicy.reserveCeiling(snapshot.lifetime())))) {
                return new Decision(null, Category.AI_BUDGET_EXHAUSTED);
            }
            UUID id = identifiers.get();
            Instant leaseUntil = state.now.plusSeconds(45);
            jdbc.update("""
                    INSERT INTO ai_usage_requests (id, account_id, feature, model, admitted_at, lease_until,
                        charge_through, input_bound, output_bound, input_price, output_price, reserved, charged, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_FLIGHT')
                    """, id, account, feature.name(), snapshot.price().model(), timestamp(state.now),
                    timestamp(leaseUntil), timestamp(leaseUntil), inputBound, outputBound,
                    snapshot.price().inputPerMillion(), snapshot.price().outputPerMillion(), reserved, reserved);
            return new Decision(new Reservation(id, leaseUntil), null);
        });
        if (decision.denial != null) { throw new AiUsageException(decision.denial); }
        return decision.reservation;
    }

    /** Exactly one terminal result wins. Unknown/failure/cancellation must pass null usage. */
    public void finish(UUID id, SoclaasClient.Usage usage) {
        Objects.requireNonNull(id);
        locked(state -> {
            expire(state.now);
            var rows = jdbc.query("""
                    SELECT model, input_price, output_price, input_bound, output_bound
                    FROM ai_usage_requests WHERE id = ? AND outcome = 'IN_FLIGHT'
                    """, (row, index) -> new Pending(new Price(row.getString("model"), row.getLong("input_price"),
                            row.getLong("output_price")), row.getInt("input_bound"), row.getInt("output_bound")), id);
            if (rows.isEmpty()) { return false; }
            Pending pending = rows.getFirst();
            boolean valid = usage != null && usage.promptTokens() >= 0 && usage.completionTokens() >= 0
                    && usage.promptTokens() <= pending.input && usage.completionTokens() <= pending.output
                    && usage.totalTokens() == usage.promptTokens() + usage.completionTokens();
            if (valid) {
                jdbc.update("""
                        UPDATE ai_usage_requests SET outcome = 'RECONCILED', finished_at = ?,
                            input_used = ?, output_used = ?, charged = ? WHERE id = ?
                        """, timestamp(state.now), usage.promptTokens(), usage.completionTokens(),
                        pending.price.cost(usage.promptTokens(), usage.completionTokens()), id);
            } else {
                jdbc.update("UPDATE ai_usage_requests SET outcome = 'AMBIGUOUS', finished_at = ? WHERE id = ?",
                        timestamp(state.now), id);
            }
            return true;
        });
    }

    /** No refunds: recovery is bounded by the admitted concurrency, not an unbounded history sweep. */
    public int recoverExpired() {
        return locked(state -> expire(state.now));
    }

    private int expire(Instant now) {
        return jdbc.update("""
                UPDATE ai_usage_requests SET outcome = 'EXPIRED', finished_at = lease_until
                WHERE outcome = 'IN_FLIGHT' AND lease_until <= ?
                """, timestamp(now));
    }

    private <T> T locked(Function<State, T> operation) {
        try {
            return transactions.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                Instant lastSeen = jdbc.queryForObject("SELECT last_seen FROM ai_usage_control WHERE id = 1 FOR UPDATE",
                        (row, index) -> instant(row, "last_seen"));
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (lastSeen != null && now.isBefore(lastSeen)) { throw new AiUsageException(Category.AI_UNAVAILABLE); }
                // Once any instance lowers a limit, an older configuration cannot raise shared capacity.
                jdbc.update("""
                        UPDATE ai_usage_control SET last_seen = ?, account_minute = LEAST(account_minute, ?),
                            account_hour = LEAST(account_hour, ?), account_day = LEAST(account_day, ?),
                            global_minute = LEAST(global_minute, ?), concurrent_limit = LEAST(concurrent_limit, ?),
                            daily_limit = LEAST(daily_limit, ?), monthly_limit = LEAST(monthly_limit, ?),
                            lifetime_limit = LEAST(lifetime_limit, ?) WHERE id = 1
                        """, timestamp(now), configured.accountMinute(), configured.accountHour(), configured.accountDay(),
                        configured.globalMinute(), configured.concurrent(), configured.daily(), configured.monthly(),
                        configured.lifetime());
                return operation.apply(jdbc.queryForObject("SELECT * FROM ai_usage_control WHERE id = 1",
                        (row, index) -> state(row, now)));
            });
        } catch (AiUsageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // JDBC exceptions can include bound values and server diagnostics. Never propagate them.
            throw new AiUsageException(Category.AI_UNAVAILABLE);
        }
    }

    private static State state(ResultSet row, Instant now) throws SQLException {
        Limits limits = new Limits(row.getInt("account_minute"), row.getInt("account_hour"), row.getInt("account_day"),
                row.getInt("global_minute"), row.getInt("concurrent_limit"), row.getLong("daily_limit"),
                row.getLong("monthly_limit"), row.getLong("lifetime_limit"));
        Instant verifiedAt = instant(row, "verified_at");
        Snapshot snapshot = verifiedAt == null ? null : new Snapshot(verifiedAt, row.getInt("provider_minute"),
                row.getLong("daily_allowance"), row.getLong("monthly_allowance"),
                row.getObject("lifetime_allowance", Long.class), row.getLong("day_spent"), row.getLong("month_spent"),
                row.getLong("lifetime_spent"), new Price(row.getString("model"), row.getLong("input_price"),
                        row.getLong("output_price")));
        return new State(now, limits, snapshot);
    }

    private long count(String condition, Object... values) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_requests WHERE " + condition, Long.class, values);
    }

    private BigInteger spendSince(Instant start) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(charged), 0) FROM ai_usage_requests WHERE charge_through >= ?
                """, BigDecimal.class, timestamp(start)).toBigIntegerExact();
    }

    private BigInteger totalSpend() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(charged), 0) FROM ai_usage_requests",
                BigDecimal.class).toBigIntegerExact();
    }

    private static boolean exceeds(BigInteger local, long observed, long reservation, long ceiling) {
        return ceiling == 0 || local.add(BigInteger.valueOf(observed)).add(BigInteger.valueOf(reservation))
                .compareTo(BigInteger.valueOf(ceiling)) > 0;
    }

    private static Instant utcDay(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static Instant utcMonth(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }
    private record State(Instant now, Limits limits, Snapshot snapshot) { }
    private record Decision(Reservation reservation, Category denial) { }
    private record Pending(Price price, int input, int output) { }
}
