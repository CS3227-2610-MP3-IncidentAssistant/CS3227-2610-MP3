package com.company.incidentdesk.web.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Keeps an unconfigured process deny-by-default without creating a generated user. */
@Configuration(proxyBeanMethods = false)
class RejectingUserDetailsConfiguration {
    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService rejectingUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Authentication failed");
        };
    }
}
