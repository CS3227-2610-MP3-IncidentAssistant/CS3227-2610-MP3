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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class AdministratorStatisticsSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(
                    PersistenceExceptionTranslationPostProcessor.class,
                    PersistenceExceptionTranslationPostProcessor::new)
            .withUserConfiguration(StatisticsStoreConfiguration.class);

    @Test
    void createsRepositoryExceptionTranslationProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AdministratorStatisticsStore.class);
            assertThat(AopUtils.isAopProxy(context.getBean(AdministratorStatisticsStore.class))).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdbcAdministratorStatisticsStore.class)
    static class StatisticsStoreConfiguration {}
}
