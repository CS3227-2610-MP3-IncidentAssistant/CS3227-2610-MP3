package com.company.incidentdesk.web.security;

import java.util.Optional;
import java.util.List;

import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.persistence.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Supplies the current trusted actor and forces creation of the SPA CSRF cookie. */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    private final AccountRepository accounts;

    public SessionController() {
        this.accounts = null;
    }

    @Autowired(required = false)
    public SessionController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/session")
    ResponseEntity<SessionRepresentation> session(Authentication authentication, CsrfToken csrf) {
        csrf.getToken();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
        String role = authenticated
                ? authentication.getAuthorities().stream()
                        .map(Object::toString)
                        .filter(value -> value.startsWith("ROLE_"))
                        .map(value -> value.substring(5))
                        .findFirst()
                        .orElse(null)
                : null;
        List<String> responderCategories = authenticated && Role.RESPONDER.name().equals(role) && accounts != null
                ? accounts.findByLoginName(authentication.getName())
                        .filter(Account::isEnabled)
                        .filter(account -> account.role() == Role.RESPONDER)
                        .map(account -> account.responderAccess().categories().stream()
                                .sorted().map(Enum::name).toList())
                        .orElseGet(List::of)
                : List.of();
        boolean credentialTemporary = authenticated && authentication.getAuthorities().stream()
                .anyMatch(authority -> DatabaseAccountDetailsService.TEMPORARY_CREDENTIAL_AUTHORITY
                        .equals(authority.getAuthority()));
        SessionRepresentation body = new SessionRepresentation(
                authenticated,
                authenticated ? Optional.of(authentication.getName()) : Optional.empty(),
                Optional.ofNullable(role),
                responderCategories,
                credentialTemporary);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record SessionRepresentation(
            boolean authenticated,
            Optional<String> loginName,
            Optional<String> role,
            List<String> responderCategories,
            boolean credentialTemporary) {
    }
}
