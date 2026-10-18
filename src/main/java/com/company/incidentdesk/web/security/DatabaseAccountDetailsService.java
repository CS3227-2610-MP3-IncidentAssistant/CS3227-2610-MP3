package com.company.incidentdesk.web.security;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads current, enabled account credentials without weakening generic login failures. */
@Service
@ConditionalOnBean(DataSource.class)
public final class DatabaseAccountDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public DatabaseAccountDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String loginName) throws UsernameNotFoundException {
        return jdbc.query("""
                SELECT a.login_name, a.role, a.status, c.password_hash
                  FROM accounts a
                  JOIN account_credentials c ON c.account_id = a.id
                 WHERE a.login_name = ?
                """, (result, row) -> User.withUsername(result.getString("login_name"))
                        .password(result.getString("password_hash"))
                        .roles(result.getString("role"))
                        .disabled(!"ENABLED".equals(result.getString("status")))
                        .build(), loginName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Authentication failed"));
    }
}
