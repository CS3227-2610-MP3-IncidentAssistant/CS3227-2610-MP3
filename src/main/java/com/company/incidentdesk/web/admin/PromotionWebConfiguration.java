package com.company.incidentdesk.web.admin;

import java.time.Clock;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.application.account.PromotionWorkflowStore;
import com.company.incidentdesk.application.audit.AuditEventFactory;
import com.company.incidentdesk.application.authorization.AccountAuthorizationPolicy;
import com.company.incidentdesk.application.session.SessionProvider;
import com.company.incidentdesk.domain.audit.AuditEventId;
import com.company.incidentdesk.persistence.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the existing promotion application service to the authenticated web and PostgreSQL boundaries. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
class PromotionWebConfiguration {
    @Bean
    Clock promotionClock() {
        return Clock.systemUTC();
    }

    @Bean
    SessionProvider promotionSessionProvider(AccountRepository accounts, Clock promotionClock) {
        return new WebPromotionSessionProvider(accounts, promotionClock);
    }

    @Bean
    PromotionRequestService promotionRequestService(
            SessionProvider promotionSessionProvider,
            AccountRepository accounts,
            PromotionWorkflowStore store,
            Clock promotionClock) {
        return new PromotionRequestService(
                promotionSessionProvider,
                new AccountAuthorizationPolicy(promotionSessionProvider),
                accounts,
                store,
                new AuditEventFactory(promotionClock, () -> new AuditEventId(UUID.randomUUID())),
                promotionClock,
                () -> new com.company.incidentdesk.domain.account.PromotionRequestId(UUID.randomUUID()),
                ignored -> { });
    }

    @Bean
    AdministratorAccountService administratorAccountService(
            AdministratorAccountStore store,
            Clock promotionClock) {
        return new AdministratorAccountService(store, promotionClock, UUID::randomUUID);
    }

    @Bean
    AdministratorSloService administratorSloService(
            AdministratorSloStore store,
            Clock promotionClock) {
        return new AdministratorSloService(store, promotionClock, UUID::randomUUID);
    }
}
