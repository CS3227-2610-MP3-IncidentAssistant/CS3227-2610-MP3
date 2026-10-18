package com.company.incidentdesk.web.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Same-origin browser security for the React application and administrator API. */
@Configuration(proxyBeanMethods = false)
public class WebSecurityConfiguration {
    private static final String GENERIC_LOGIN_FAILURE = "{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Sign-in failed\"}";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http, UserDetailsService users) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        csrf.setHeaderName("X-CSRF-TOKEN");

        http
                .userDetailsService(users)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/assets/**", "/health", "/api/v1/auth/session",
                                "/api/v1/auth/register")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMINISTRATOR")
                        .requestMatchers("/api/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .csrf(csrfConfig -> csrfConfig.csrfTokenRepository(csrf))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/auth/administrator/login")
                        .successHandler(administratorSuccessHandler())
                        .failureHandler((request, response, exception) -> writeJson(
                                response, HttpServletResponse.SC_UNAUTHORIZED, GENERIC_LOGIN_FAILURE))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION", "JSESSIONID", "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeJson(response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication is required\"}"))
                        .accessDeniedHandler((request, response, exception) -> writeJson(response,
                                HttpServletResponse.SC_NOT_FOUND,
                                "{\"code\":\"UNAVAILABLE\",\"message\":\"Resource unavailable\"}")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    private static AuthenticationSuccessHandler administratorSuccessHandler() {
        return (request, response, authentication) -> {
            if (!authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))) {
                rejectWrongPortal(request, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setHeader("Cache-Control", "no-store");
        };
    }

    private static void rejectWrongPortal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
    }

    private static void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json);
    }
}
