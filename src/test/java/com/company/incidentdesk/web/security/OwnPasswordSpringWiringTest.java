package com.company.incidentdesk.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** The own-password components must resolve and stay proxyable for exception translation. */
class OwnPasswordSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(WebSecurityConfiguration.SECURITY_CLOCK, Clock.class, Clock::systemUTC)
            .withBean(PasswordEncoder.class, NoOpPasswordEncoder::getInstance)
            .withBean(
                    PersistenceExceptionTranslationPostProcessor.class,
                    PersistenceExceptionTranslationPostProcessor::new)
            .withUserConfiguration(OwnPasswordConfiguration.class);

    @Test
    void resolvesAgainstTheApplicationClockBeanTheRealContextProvides() {
        // Production supplies the Clock from PromotionWebConfiguration, which is also gated on a
        // DataSource. Asserting the dependency here keeps a missing Clock from surfacing at startup.
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(PasswordEncoder.class, NoOpPasswordEncoder::getInstance)
                .withUserConfiguration(OwnPasswordConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void usesTheSecurityClockRatherThanAnyUnqualifiedClock() {
        contextRunner
                .withBean("unrelatedClock", Clock.class, () -> Clock.systemUTC())
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void createsRepositoryExceptionTranslationProxyAndController() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OwnPasswordService.class);
            assertThat(AopUtils.isAopProxy(context.getBean(OwnPasswordService.class))).isTrue();
            assertThat(context).hasSingleBean(OwnPasswordController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ OwnPasswordService.class, OwnPasswordController.class })
    static class OwnPasswordConfiguration {}
}
