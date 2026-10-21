package com.company.incidentdesk.web.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.company.incidentdesk.domain.incident.IncidentCategory;

/** Validates and coordinates administrator SLO reads and version changes. */
final class AdministratorSloService {
    private final AdministratorSloStore store;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    AdministratorSloService(AdministratorSloStore store, Clock clock, Supplier<UUID> identifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    SloConfiguration configuration(String administratorLoginName) {
        AdministratorSloStore.ReadResult result = store.readAll(requireActor(administratorLoginName));
        if (result.outcome() != AdministratorSloStore.ReadOutcome.AVAILABLE) {
            return SloConfiguration.failure(result.outcome());
        }
        Map<String, List<AdministratorSloStore.SloVersion>> byCategory = new LinkedHashMap<>();
        for (IncidentCategory category : IncidentCategory.values()) {
            byCategory.put(category.name(), new ArrayList<>());
        }
        result.versions().forEach(version -> byCategory.get(version.category()).add(version));
        List<CategoryConfiguration> categories = byCategory.entrySet().stream()
                .map(entry -> categoryConfiguration(entry.getKey(), entry.getValue()))
                .toList();
        return new SloConfiguration(result.outcome(), categories);
    }

    private static CategoryConfiguration categoryConfiguration(
            String category,
            List<AdministratorSloStore.SloVersion> storedVersions) {
        List<SloVersionView> history = storedVersions.stream()
                .map(AdministratorSloService::toView)
                .toList();
        SloVersionView current = history.isEmpty() ? null : history.getLast();
        return new CategoryConfiguration(category, current, history);
    }

    private static SloVersionView toView(AdministratorSloStore.SloVersion storedVersion) {
        return new SloVersionView(
                storedVersion.id(),
                storedVersion.category(),
                storedVersion.timeToClaimMinutes(),
                storedVersion.timeInProgressMinutes(),
                storedVersion.reopenRatePercent(),
                storedVersion.effectiveAt());
    }

    ChangeResult change(String administratorLoginName, String rawCategory, ChangeRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> errors = validate(request);
        String category = category(rawCategory, errors);
        if (!errors.isEmpty()) return ChangeResult.validation(errors);
        BigDecimal percent = request.reopenRatePercent().setScale(2, RoundingMode.UNNECESSARY);
        AdministratorSloStore.ChangeOutcome outcome = store.change(
                requireActor(administratorLoginName), category, request.expectedVersionId(),
                request.timeToClaimMinutes(), request.timeInProgressMinutes(), percent,
                identifiers.get(), identifiers.get(), clock.instant());
        return new ChangeResult(outcome, Map.of());
    }

    private static Map<String, String> validate(ChangeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.timeToClaimMinutes() == null || request.timeToClaimMinutes() < 0) {
            errors.put("timeToClaimMinutes", "Enter a non-negative whole number of minutes");
        }
        if (request.timeInProgressMinutes() == null || request.timeInProgressMinutes() < 0) {
            errors.put("timeInProgressMinutes", "Enter a non-negative whole number of minutes");
        }
        BigDecimal percent = request.reopenRatePercent();
        if (percent == null || percent.compareTo(BigDecimal.ZERO) < 0
                || percent.compareTo(BigDecimal.valueOf(100)) > 0 || percent.scale() > 2) {
            errors.put("reopenRatePercent", "Enter a percentage from 0 through 100 with at most two decimals");
        }
        return errors;
    }

    private static String category(String rawCategory, Map<String, String> errors) {
        try {
            return IncidentCategory.valueOf(Objects.requireNonNull(rawCategory, "category")).name();
        } catch (IllegalArgumentException | NullPointerException exception) {
            errors.put("category", "Select a supported incident category");
            return "";
        }
    }

    private static String requireActor(String loginName) {
        Objects.requireNonNull(loginName, "administratorLoginName");
        if (loginName.isBlank()) throw new IllegalArgumentException("administratorLoginName must not be blank");
        return loginName;
    }

    record ChangeRequest(
            Long timeToClaimMinutes,
            Long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            UUID expectedVersionId) { }

    record ChangeResult(AdministratorSloStore.ChangeOutcome outcome, Map<String, String> fieldErrors) {
        ChangeResult {
            fieldErrors = Map.copyOf(fieldErrors);
        }

        static ChangeResult validation(Map<String, String> errors) {
            return new ChangeResult(null, errors);
        }

        boolean isValidationFailure() {
            return outcome == null;
        }
    }

    record SloConfiguration(
            AdministratorSloStore.ReadOutcome outcome,
            List<CategoryConfiguration> categories) {
        SloConfiguration {
            categories = List.copyOf(categories);
        }

        static SloConfiguration failure(AdministratorSloStore.ReadOutcome outcome) {
            return new SloConfiguration(outcome, List.of());
        }
    }

    record CategoryConfiguration(
            String category,
            SloVersionView current,
            List<SloVersionView> history) {
        CategoryConfiguration {
            history = List.copyOf(history);
        }
    }

    record SloVersionView(
            UUID id,
            String category,
            long timeToClaimMinutes,
            long timeInProgressMinutes,
            BigDecimal reopenRatePercent,
            Instant effectiveAt) { }
}
