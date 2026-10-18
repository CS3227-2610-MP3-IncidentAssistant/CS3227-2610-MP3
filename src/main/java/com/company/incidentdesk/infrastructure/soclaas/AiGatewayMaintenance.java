package com.company.incidentdesk.infrastructure.soclaas;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;

/** One bounded background worker; PostgreSQL elects the metadata refresher across instances. */
final class AiGatewayMaintenance implements AutoCloseable {
    private final SoclaasSettings settings;
    private final AiGatewayControl control;
    private final SoclaasClient client;
    private final Supplier<SoclaasBudgetClient.Budget> budget;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ai-metadata-", 0).factory());

    AiGatewayMaintenance(SoclaasSettings settings, AiGatewayControl control, SoclaasClient client,
            Supplier<SoclaasBudgetClient.Budget> budget, Clock clock, MeterRegistry metrics) {
        this.settings = settings;
        this.control = control;
        this.client = client;
        this.budget = budget;
        this.clock = clock;
        this.metrics = metrics;
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            runOnce();
            worker.scheduleWithFixedDelay(this::runOnce, 30, 30, TimeUnit.SECONDS);
        }
    }

    void runOnce() {
        if (!settings.enabled()) { return; }
        try {
            control.recover();
            var owner = control.claimRefresh(settings.revision());
            if (owner == null) { return; }
            try {
                var catalogAt = clock.instant();
                var price = AiUsagePolicy.Price.fromCatalog(client.validateModel());
                var snapshot = budget.get().snapshot(price, catalogAt);
                control.publish(owner, settings.revision(), snapshot);
                metrics.counter("incidentdesk.ai.metadata", "outcome", "SUCCESS").increment();
            } catch (RuntimeException unsafe) {
                var safe = unsafe instanceof SoclaasException provider ? provider
                        : new SoclaasException(SoclaasException.Category.AI_UNAVAILABLE);
                control.failRefresh(owner, safe);
                metrics.counter("incidentdesk.ai.metadata", "outcome", safe.category().name()).increment();
            }
        } catch (RuntimeException unavailable) {
            // A failed refresh/recovery must neither kill the scheduler nor leak database/provider diagnostics.
            metrics.counter("incidentdesk.ai.metadata", "outcome", "AI_UNAVAILABLE").increment();
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
