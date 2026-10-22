package com.company.incidentdesk.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.company.incidentdesk.application.account.PromotionRequestService;
import com.company.incidentdesk.domain.account.Account;
import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.AccountStatus;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.ResponderAccess;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.domain.account.Role;
import com.company.incidentdesk.domain.incident.IncidentCategory;
import com.company.incidentdesk.persistence.AccountRepository;

class PromotionRequestControllerTest {
    private static final Account REPORTER = account("reporter", Role.REPORTER);
    private static final Account ADMINISTRATOR = account("admin", Role.ADMINISTRATOR);
    private static final Instant REQUESTED_AT = Instant.parse("2026-10-22T03:00:00Z");

    private PromotionRequestService promotions;
    private AccountRepository accounts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        promotions = mock(PromotionRequestService.class);
        accounts = mock(AccountRepository.class);
        when(accounts.findByLoginName(REPORTER.loginName())).thenReturn(Optional.of(REPORTER));
        when(accounts.findByLoginName(ADMINISTRATOR.loginName())).thenReturn(Optional.of(ADMINISTRATOR));
        mvc = MockMvcBuilders.standaloneSetup(new PromotionRequestController(promotions, accounts)).build();
    }

    @Test
    void reporterHistoryExposesABoundedNewestFirstPageContract() throws Exception {
        ResponderPromotionRequest request = ResponderPromotionRequest.pending(
                new PromotionRequestId(new UUID(0, 3)), REPORTER.id(), Set.of(IncidentCategory.IT),
                Optional.of("Qualified responder"), REQUESTED_AT);
        when(promotions.reporterHistoryAs(REPORTER, 25, 25)).thenReturn(List.of(request));
        when(promotions.countReporterHistoryAs(REPORTER)).thenReturn(51L);

        mvc.perform(get("/api/v1/reporter/promotion-request")
                        .principal(principal(REPORTER.loginName()))
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(51))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.requests[0].requestId").value(request.id().value().toString()))
                .andExpect(jsonPath("$.requests[0].requestedCategories[0]").value("IT"));

        verify(promotions).reporterHistoryAs(REPORTER, 25, 25);
    }

    @Test
    void invalidPaginationIsRejectedInsteadOfSilentlyRewritten() throws Exception {
        mvc.perform(get("/api/v1/reporter/promotion-request")
                        .principal(principal(REPORTER.loginName()))
                        .param("page", "-1")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.page").value("Invalid value."))
                .andExpect(jsonPath("$.fieldErrors.size").value("Invalid value."));

        verify(promotions, never()).reporterHistoryAs(any(), any(Long.class), any(Integer.class));
    }

    @Test
    void pageSizeAboveTheMaximumIsCappedForTheAdministratorQueue() throws Exception {
        when(promotions.pendingRequestsAs(ADMINISTRATOR, 100, 100)).thenReturn(List.of());
        when(promotions.countPendingRequestsAs(ADMINISTRATOR)).thenReturn(0L);

        mvc.perform(get("/api/v1/admin/promotion-requests")
                        .principal(principal(ADMINISTRATOR.loginName()))
                        .param("page", "1")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.requests").isEmpty());

        verify(promotions).pendingRequestsAs(ADMINISTRATOR, 100, 100);
    }

    @Test
    void malformedRequestIdentifierIsRejectedBeforeRepositoryAccess() throws Exception {
        mvc.perform(get("/api/v1/admin/promotion-requests/not-an-identifier")
                        .principal(principal(ADMINISTRATOR.loginName())))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.requestId").value("Invalid value."));

        verify(promotions, never()).findRequestAs(any(), any());
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static Account account(String name, Role role) {
        return new Account(new AccountId(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                name, role, AccountStatus.ENABLED, ResponderAccess.NONE);
    }
}
