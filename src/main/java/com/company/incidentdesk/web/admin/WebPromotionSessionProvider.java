package com.company.incidentdesk.web.admin;

import java.time.Clock;
import java.util.Optional;

import com.company.incidentdesk.application.session.AuthenticatedSession;
import com.company.incidentdesk.application.session.SessionProvider;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.persistence.AccountRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the application actor from Spring Security's request-bound principal and current account row. */
final class WebPromotionSessionProvider implements SessionProvider {
    private final AccountRepository accounts;
    private final Clock clock;

    WebPromotionSessionProvider(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public Optional<AuthenticatedSession> currentSession() {
        return currentAccount().map(account -> new AuthenticatedSession(account.id(), clock.instant()));
    }

    @Override
    public Optional<Account> currentAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return accounts.findByLoginName(authentication.getName()).filter(Account::isEnabled);
    }
}
