package com.company.incidentdesk.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

/** The account store must still resolve now that it hashes reset credentials. */
class AdministratorAccountStoreSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(PasswordEncoder.class, NoOpPasswordEncoder::getInstance)
            .withBean(
                    PersistenceExceptionTranslationPostProcessor.class,
                    PersistenceExceptionTranslationPostProcessor::new)
            .withUserConfiguration(AccountStoreConfiguration.class);

    @Test
    void createsRepositoryExceptionTranslationProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AdministratorAccountStore.class);
            assertThat(AopUtils.isAopProxy(context.getBean(AdministratorAccountStore.class))).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdbcAdministratorAccountStore.class)
    static class AccountStoreConfiguration {}
}
