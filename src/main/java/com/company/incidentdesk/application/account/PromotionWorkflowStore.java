package com.company.incidentdesk.application.account;

import java.util.List;
import java.util.Optional;

import com.company.incidentdesk.domain.account.AccountId;
import com.company.incidentdesk.domain.account.PromotionRequestId;
import com.company.incidentdesk.domain.account.PromotionRequestStatus;
import com.company.incidentdesk.domain.account.ResponderPromotionRequest;
import com.company.incidentdesk.persistence.AuditedMutation;

/** Persistence boundary for promotion history and atomic account authorization updates. */
public interface PromotionWorkflowStore {
    Optional<ResponderPromotionRequest> findPromotionRequest(PromotionRequestId requestId);

    List<ResponderPromotionRequest> findPromotionRequests();

    default List<ResponderPromotionRequest> findPromotionRequestsByRequester(AccountId requesterId) {
        return findPromotionRequests().stream()
                .filter(request -> request.requesterId().equals(requesterId))
                .toList();
    }

    default List<ResponderPromotionRequest> findPendingPromotionRequests(long offset, int limit) {
        return findPromotionRequests().stream()
                .filter(request -> request.status() == PromotionRequestStatus.PENDING)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    default long countPendingPromotionRequests() {
        return findPromotionRequests().stream()
                .filter(request -> request.status() == PromotionRequestStatus.PENDING)
                .count();
    }

    boolean hasPendingPromotionRequest(AccountId requesterId);

    void commitPromotion(AuditedMutation<PromotionWorkflowMutation> mutation);
}
