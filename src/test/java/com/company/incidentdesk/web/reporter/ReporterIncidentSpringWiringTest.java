package com.company.incidentdesk.web.reporter;

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ReporterIncidentSpringWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(PersistenceExceptionTranslationPostProcessor.class,
                    PersistenceExceptionTranslationPostProcessor::new)
            .withUserConfiguration(ReporterIncidentConfiguration.class);

    @Test
    void createsTheRepositoryExceptionTranslationProxy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReporterIncidentGateway.class);
            assertThat(AopUtils.isAopProxy(context.getBean(ReporterIncidentGateway.class))).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdbcReporterIncidentGateway.class)
    static class ReporterIncidentConfiguration {
    }
}
