package com.company.incidentdesk.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.incidentdesk.web.security.TemporaryAccountRegistrationService.RegistrationOutcome;

class JdbcTemporaryAccountRegistrationStoreTest {
    @Test
    void mapsOnlyTheLoginNameConstraintToDuplicate() {
        assertEquals(RegistrationOutcome.DUPLICATE, outcomeForConstraint("accounts_login_name_key"));
        assertEquals(RegistrationOutcome.UNAVAILABLE, outcomeForConstraint("audit_events_id_key"));
    }

    private static RegistrationOutcome outcomeForConstraint(String constraint) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ServerErrorMessage serverError = mock(ServerErrorMessage.class);
        PSQLException postgres = mock(PSQLException.class);
        when(serverError.getConstraint()).thenReturn(constraint);
        when(postgres.getServerErrorMessage()).thenReturn(serverError);
        doThrow(new DuplicateKeyException("constraint violation", postgres))
                .when(transactions).executeWithoutResult(any());

        JdbcTemporaryAccountRegistrationStore store =
                new JdbcTemporaryAccountRegistrationStore(jdbc, transactions);
        return store.register(mock(TemporaryAccountRegistration.class));
    }
}
