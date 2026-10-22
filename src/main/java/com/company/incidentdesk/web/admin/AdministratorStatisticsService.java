package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.domain.incident.IncidentCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Validates administrator statistics filters before protected persistence is queried. */
@Service
@ConditionalOnBean(DataSource.class)
final class AdministratorStatisticsService {
    private static final Set<String> CATEGORIES = Arrays.stream(IncidentCategory.values())
            .map(IncidentCategory::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final AdministratorStatisticsStore store;

    AdministratorStatisticsService(AdministratorStatisticsStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    AdministratorStatisticsStore.ReadResult read(String administratorLoginName, Query query) {
        Objects.requireNonNull(query, "query");
        String actor = requireActor(administratorLoginName);
        Set<String> categories = Set.copyOf(query.categories());
        if (!CATEGORIES.containsAll(categories)) {
            throw new IllegalArgumentException("Unknown incident category");
        }
        if (query.periodFrom() != null && query.periodThrough() != null
                && query.periodFrom().isAfter(query.periodThrough())) {
            throw new IllegalArgumentException("periodFrom must not be after periodThrough");
        }
        return store.read(actor, new AdministratorStatisticsStore.StatisticsFilter(
                categories, query.responderId(), query.reporterId(), query.periodFrom(), query.periodThrough()));
    }

    private static String requireActor(String loginName) {
        Objects.requireNonNull(loginName, "administratorLoginName");
        if (loginName.isBlank()) {
            throw new IllegalArgumentException("administratorLoginName must not be blank");
        }
        return loginName;
    }

    record Query(
            List<String> categories,
            UUID responderId,
            UUID reporterId,
            Instant periodFrom,
            Instant periodThrough) {
        Query {
            categories = categories == null ? List.of() : categories.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList();
        }
    }
}
