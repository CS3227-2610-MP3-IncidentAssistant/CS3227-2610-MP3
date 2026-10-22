package com.company.incidentdesk.web.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import com.company.incidentdesk.application.ai.insights.AdministratorInsightPeriodResolver;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.Outcome;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlan.QueryType;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightPlanParser;
import com.company.incidentdesk.application.ai.insights.AdministratorInsightQuestionValidator;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.infrastructure.soclaas.AiRequest;
import com.company.incidentdesk.infrastructure.soclaas.SecuredAiGateway;

/** Coordinates validation, one provider plan, reauthorization, analytics, and deterministic rendering. */
@Service
@ConditionalOnBean(DataSource.class)
final class AdministratorInsightService {
    private static final Set<String> CATEGORIES = Arrays.stream(IncidentCategory.values())
            .map(IncidentCategory::name).collect(Collectors.toUnmodifiableSet());

    private final AdministratorInsightAnalytics analytics;
    private final ObjectProvider<SecuredAiGateway> gateways;
    private final Clock clock;
    private final AdministratorInsightQuestionValidator questions = new AdministratorInsightQuestionValidator();
    private final AdministratorInsightPlanParser plans = new AdministratorInsightPlanParser();
    private final AdministratorInsightRenderer renderer = new AdministratorInsightRenderer();

    @Autowired
    AdministratorInsightService(AdministratorInsightAnalytics analytics, ObjectProvider<SecuredAiGateway> gateways) {
        this(analytics, gateways, Clock.systemUTC());
    }

    AdministratorInsightService(AdministratorInsightAnalytics analytics, ObjectProvider<SecuredAiGateway> gateways,
            Clock clock) {
        this.analytics = Objects.requireNonNull(analytics);
        this.gateways = Objects.requireNonNull(gateways);
        this.clock = Objects.requireNonNull(clock);
    }

    AdministratorInsightRenderer.Response answer(String administratorLoginName, Request request) {
        String actor = requireActor(administratorLoginName);
        Request validatedRequest = validateSelections(request);
        List<String> knownLoginNames;
        try {
            knownLoginNames = analytics.knownLoginNames(actor);
        } catch (JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException unavailable) {
            throw new InsightUnavailableException();
        }
        var question = questions.validate(validatedRequest.question(), knownLoginNames);
        Instant requestInstant = clock.instant();
        SecuredAiGateway gateway = gateways.getIfAvailable();
        if (gateway == null) throw new AiUnavailableException();

        var plan = plans.parse(gateway.assist(new AiRequest.AdministratorInsight(
                question.value(), requestInstant)).text());
        if (plan.outcome() == Outcome.NEEDS_CLARIFICATION) {
            return renderer.clarification(plan.clarificationCode().orElseThrow(), requestInstant);
        }
        validatePlanSelections(plan.queryType().orElseThrow(), validatedRequest.responderIds());
        var periods = new AdministratorInsightPeriodResolver(
                Clock.fixed(requestInstant, ZoneOffset.UTC)).resolve(plan);
        try {
            var result = analytics.execute(actor, new AdministratorInsightAnalytics.Query(plan, periods,
                    Set.copyOf(validatedRequest.categories()), Set.copyOf(validatedRequest.responderIds())));
            return renderer.answer(plan, periods, result);
        } catch (JdbcAdministratorInsightAnalytics.AnalyticsUnavailableException unavailable) {
            throw new InsightUnavailableException();
        }
    }

    private static Request validateSelections(Request request) {
        boolean invalid = request == null || request.question() == null || request.categories().size() > 10
                || request.responderIds().size() > 5 || !CATEGORIES.containsAll(request.categories())
                || request.categories().stream().distinct().count() != request.categories().size()
                || request.responderIds().stream().distinct().count() != request.responderIds().size();
        if (invalid) throw new InvalidInsightRequestException();
        return request;
    }

    private static void validatePlanSelections(QueryType queryType, List<UUID> responderIds) {
        if (queryType == QueryType.COMPARE_SELECTED_RESPONDERS
                && (responderIds.size() < 2 || responderIds.size() > 5)) {
            throw new InvalidInsightRequestException();
        }
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new InsightUnavailableException();
        return actor;
    }

    record Request(String question, List<String> categories, List<UUID> responderIds) {
        Request {
            categories = categories == null ? List.of() : List.copyOf(categories);
            responderIds = responderIds == null ? List.of() : List.copyOf(responderIds);
        }
    }
    static final class InvalidInsightRequestException extends RuntimeException { }
    static final class InsightUnavailableException extends RuntimeException { }
    static final class AiUnavailableException extends RuntimeException { }
}
