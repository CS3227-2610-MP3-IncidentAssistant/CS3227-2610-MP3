package com.company.incidentdesk.infrastructure.soclaas;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;

/** Trusted backend entry point. No actor, provider settings or prompt policy is accepted from the caller. */
public final class SecuredAiGateway implements AutoCloseable {
    private final SoclaasSettings settings;
    private final SoclaasClient client;
    private final AiGatewayControl control;
    private final JdbcTemplate jdbc;
    private final AiDataSelector selector;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor workers;

    SecuredAiGateway(SoclaasSettings settings, SoclaasClient client, AiGatewayControl control, JdbcTemplate jdbc,
            AiDataSelector selector, Clock clock, MeterRegistry metrics) {
        this.settings = settings;
        this.client = client;
        this.control = control;
        this.jdbc = jdbc;
        this.selector = selector;
        this.clock = clock;
        this.metrics = metrics;
        workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("ai-provider-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Invoke outside database transactions so slow provider I/O cannot retain a caller's locks/connections. */
    public Suggestion assist(AiRequest request) {
        String feature = request == null ? "INVALID" : request.feature().name();
        String outcome = "AI_UNAVAILABLE";
        try {
            if (!settings.enabled() || TransactionSynchronizationManager.isActualTransactionActive()) {
                throw failure(SoclaasException.Category.AI_UNAVAILABLE);
            }
            if (request == null) { throw failure(SoclaasException.Category.AI_REJECTED); }
            UUID actor = actor(request);
            var prepared = AiPromptBuilder.prepare(request, actor, selector);
            int bytes = prepared.data().getBytes(StandardCharsets.UTF_8).length;
            long input = (long) bytes + prepared.system().getBytes(StandardCharsets.UTF_8).length + 256;
            if (bytes > settings.maxInputBytes() || input > settings.maxInputTokens()) {
                throw failure(SoclaasException.Category.AI_REJECTED);
            }
            var ticket = control.admit(actor, request.feature(), settings.model(), (int) input,
                    settings.maxOutputTokens(), settings.revision());
            BooleanSupplier deliveryAllowed = request instanceof AiRequest.ResponderIncident target
                    ? () -> selector.responderMayView(actor, target.incidentId()) : () -> true;
            Suggestion result = dispatch(ticket, prepared, deliveryAllowed);
            outcome = "SUCCESS";
            return result;
        } catch (AiUsageException safe) {
            outcome = safe.category().name();
            throw failure(SoclaasException.Category.valueOf(outcome));
        } catch (SoclaasException safe) {
            outcome = safe.category().name();
            // Do not expose upstream status or Retry-After/key-wide information to feature callers.
            throw failure(safe.category());
        } catch (RuntimeException unsafe) {
            throw failure(SoclaasException.Category.AI_UNAVAILABLE);
        } finally {
            metrics.counter("incidentdesk.ai.requests", "feature", feature, "outcome", outcome).increment();
        }
    }

    private UUID actor(AiRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String role = AiGatewayControl.role(request.feature());
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
                || authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_" + role))) {
            throw failure(SoclaasException.Category.AI_REJECTED);
        }
        var actors = jdbc.query("SELECT id FROM accounts WHERE login_name=? AND role=? AND status='ENABLED'",
                (row, index) -> row.getObject(1, UUID.class), authentication.getName(), role);
        if (actors.size() != 1) { throw failure(SoclaasException.Category.AI_REJECTED); }
        return actors.getFirst();
    }

    private Suggestion dispatch(AiGatewayControl.Ticket ticket, AiPromptBuilder.Prepared prepared,
            BooleanSupplier deliveryAllowed) {
        Future<SoclaasClient.Completion> pending = null;
        SoclaasClient.Completion completion = null;
        SoclaasException error = null;
        boolean interrupted = false;
        try {
            long remaining = Math.min(settings.requestTimeout().toNanos(), Duration.between(clock.instant(), ticket.leaseUntil()).toNanos());
            if (remaining <= 0) { throw failure(SoclaasException.Category.AI_TIMEOUT); }
            pending = workers.submit(() -> client.completeAdmitted(ticket, prepared.system(), prepared.data()));
            completion = pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
            interrupted = true;
            error = failure(SoclaasException.Category.AI_UNAVAILABLE);
        } catch (TimeoutException timeout) {
            error = failure(SoclaasException.Category.AI_TIMEOUT);
        } catch (RejectedExecutionException busy) {
            error = failure(SoclaasException.Category.AI_RATE_LIMITED);
        } catch (ExecutionException unsafe) {
            error = unsafe.getCause() instanceof SoclaasException safe ? safe : failure(SoclaasException.Category.AI_UNAVAILABLE);
        } catch (SoclaasException safe) { error = safe; }
        finally { if (error != null && pending != null) { pending.cancel(true); } }
        // Persist a terminal audit even on caller interruption, then restore cancellation for the enclosing web request.
        interrupted |= Thread.interrupted();
        try {
            if (completion != null && error == null) {
                try {
                    if (!deliveryAllowed.getAsBoolean()) { error = failure(SoclaasException.Category.AI_REJECTED); }
                } catch (RuntimeException unsafe) { error = failure(SoclaasException.Category.AI_UNAVAILABLE); }
            }
            var terminal = control.finish(ticket, completion == null ? null : completion.usage(), error);
            if (terminal != null) { throw failure(terminal); }
            if (completion == null) { throw failure(SoclaasException.Category.AI_UNAVAILABLE); }
            return new Suggestion(completion.text());
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private static SoclaasException failure(SoclaasException.Category category) { return new SoclaasException(category); }
    @Override public void close() { workers.shutdownNow(); }

    /** Untrusted plain text: the later browser integration must render it using escaped text nodes. */
    public record Suggestion(String text) {
        @Override public String toString() { return "AI suggestion [content withheld]"; }
    }
}
