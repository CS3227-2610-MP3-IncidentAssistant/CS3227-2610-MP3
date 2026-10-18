package com.company.incidentdesk.web.security;

import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Supplies the browser's current trusted actor and CSRF request token. */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    @GetMapping("/session")
    ResponseEntity<SessionRepresentation> session(Authentication authentication, CsrfToken csrf) {
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
        SessionRepresentation body = new SessionRepresentation(
                authenticated,
                authenticated ? Optional.of(authentication.getName()) : Optional.empty(),
                Optional.ofNullable(role),
                csrf.getHeaderName(),
                csrf.getToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record SessionRepresentation(
            boolean authenticated,
            Optional<String> loginName,
            Optional<String> role,
            String csrfHeader,
            String csrfToken) {
    }
}
