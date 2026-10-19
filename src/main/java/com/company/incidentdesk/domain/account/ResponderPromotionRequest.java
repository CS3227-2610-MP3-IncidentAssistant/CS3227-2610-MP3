package com.company.incidentdesk.domain.account;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.company.incidentdesk.domain.incident.IncidentCategory;

/** Immutable, retained history of a reporter's responder-promotion request. */
public record ResponderPromotionRequest(
        PromotionRequestId id,
        AccountId requesterId,
        Set<IncidentCategory> requestedCategories,
        Optional<String> comments,
        Instant requestedAt,
        PromotionRequestStatus status,
        Optional<AccountId> decidedBy,
        Optional<Instant> decidedAt,
        Optional<Set<IncidentCategory>> approvedCategories) {
    public ResponderPromotionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(requestedCategories, "requestedCategories");
        Objects.requireNonNull(comments, "comments");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(approvedCategories, "approvedCategories");
        if (requestedCategories.isEmpty()) {
            throw new IllegalArgumentException("requestedCategories must not be empty");
        }
        requestedCategories = Set.copyOf(EnumSet.copyOf(requestedCategories));
        approvedCategories = approvedCategories.map(categories -> Set.copyOf(categories));
        comments = comments.map(String::trim).filter(value -> !value.isEmpty());
        if (status == PromotionRequestStatus.PENDING && (decidedBy.isPresent() || decidedAt.isPresent())) {
            throw new IllegalArgumentException("pending request cannot contain decision details");
        }
        if (status == PromotionRequestStatus.PENDING && approvedCategories.isPresent()) {
            throw new IllegalArgumentException("pending request cannot contain approved categories");
        }
        if (status != PromotionRequestStatus.PENDING && (decidedBy.isEmpty() || decidedAt.isEmpty())) {
            throw new IllegalArgumentException("decided request requires decision details");
        }
        if (status == PromotionRequestStatus.APPROVED
                && (approvedCategories.isEmpty() || approvedCategories.orElseThrow().isEmpty()
                        || !requestedCategories.containsAll(approvedCategories.orElseThrow()))) {
            throw new IllegalArgumentException("approved request requires a non-empty requested-category subset");
        }
        if (status == PromotionRequestStatus.REJECTED
                && approvedCategories.filter(Set::isEmpty).isEmpty()) {
            throw new IllegalArgumentException("rejected request cannot grant categories");
        }
    }

    public static ResponderPromotionRequest pending(PromotionRequestId id, AccountId requesterId,
            Set<IncidentCategory> categories, Optional<String> comments, Instant requestedAt) {
        return new ResponderPromotionRequest(id, requesterId, categories, comments, requestedAt,
                PromotionRequestStatus.PENDING, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public ResponderPromotionRequest decide(
            boolean approved,
            Set<IncidentCategory> categoriesToApprove,
            AccountId administratorId,
            Instant decidedAt) {
        if (status != PromotionRequestStatus.PENDING) {
            throw new IllegalStateException("promotion request has already been decided");
        }
        Objects.requireNonNull(categoriesToApprove, "categoriesToApprove");
        Set<IncidentCategory> grants = Set.copyOf(categoriesToApprove);
        if (approved && (grants.isEmpty() || !requestedCategories.containsAll(grants))) {
            throw new IllegalArgumentException("approved categories must be a non-empty subset of the request");
        }
        if (!approved && !grants.isEmpty()) {
            throw new IllegalArgumentException("rejected requests cannot grant categories");
        }
        return new ResponderPromotionRequest(id, requesterId, requestedCategories, comments, requestedAt,
                approved ? PromotionRequestStatus.APPROVED : PromotionRequestStatus.REJECTED,
                Optional.of(administratorId), Optional.of(decidedAt), Optional.of(grants));
    }
}
