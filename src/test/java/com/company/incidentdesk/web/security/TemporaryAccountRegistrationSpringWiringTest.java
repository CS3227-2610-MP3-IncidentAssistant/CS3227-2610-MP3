package com.company.incidentdesk.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class TemporaryAccountRegistrationSpringWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(PersistenceExceptionTranslationPostProcessor.class,
                    PersistenceExceptionTranslationPostProcessor::new)
            .withUserConfiguration(RegistrationConfiguration.class);

    @Test
    void createsTheRegistrationControllerServiceAndRepositoryProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TemporaryAccountRegistrationController.class);
            assertThat(context).hasSingleBean(TemporaryAccountRegistrationService.class);
            assertThat(context).hasSingleBean(TemporaryAccountRegistrationStore.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            TemporaryAccountRegistrationController.class,
            TemporaryAccountRegistrationService.class,
            JdbcTemporaryAccountRegistrationStore.class
    })
    static class RegistrationConfiguration {
    }
}
