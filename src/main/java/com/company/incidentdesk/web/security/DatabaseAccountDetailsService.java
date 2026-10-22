package com.company.incidentdesk.web.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads current, enabled account credentials without weakening generic login failures. */
@Service
@ConditionalOnBean(DataSource.class)
public final class DatabaseAccountDetailsService implements UserDetailsService {
    /**
     * Marks a session established with an unexpired temporary credential. The holder must
     * replace the password before any role workflow becomes available.
     */
    public static final String TEMPORARY_CREDENTIAL_AUTHORITY = "CREDENTIAL_TEMPORARY";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public DatabaseAccountDetailsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public DatabaseAccountDetailsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public UserDetails loadUserByUsername(String loginName) throws UsernameNotFoundException {
        return jdbc.query("""
                SELECT a.login_name, a.role, a.status, c.password_hash, c.temporary, c.expires_at
                  FROM accounts a
                  JOIN account_credentials c ON c.account_id = a.id
                 WHERE a.login_name = ?
                """, (result, row) -> {
                    boolean temporary = result.getBoolean("temporary");
                    Timestamp expiresAt = result.getTimestamp("expires_at");
                    // An expired temporary credential must never authenticate again. Disabling the
                    // account here keeps the failure identical to any other rejected sign-in.
                    boolean expired = temporary && expiresAt != null
                            && !clock.instant().isBefore(expiresAt.toInstant());
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + result.getString("role")));
                    if (temporary) authorities.add(new SimpleGrantedAuthority(TEMPORARY_CREDENTIAL_AUTHORITY));
                    return User.withUsername(result.getString("login_name"))
                            .password(result.getString("password_hash"))
                            .authorities(authorities)
                            .disabled(expired || !"ENABLED".equals(result.getString("status")))
                            .build();
                }, loginName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Authentication failed"));
    }
}
