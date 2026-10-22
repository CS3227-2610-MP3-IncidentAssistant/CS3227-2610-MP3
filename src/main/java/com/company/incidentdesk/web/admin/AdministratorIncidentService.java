package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Application boundary for privacy-safe Administrator incident detail operations. */
@Service
@ConditionalOnBean(DataSource.class)
final class AdministratorIncidentService {
    static final int MAX_COMMENT_LENGTH = 4_000;
    static final int MAX_RESOLUTION_REMARKS_LENGTH = 2_000;

    private final AdministratorIncidentStore incidents;

    AdministratorIncidentService(AdministratorIncidentStore incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
    }

    IncidentDetail detail(String loginName, UUID incidentId) {
        return incidents.findDetail(requireLoginName(loginName), Objects.requireNonNull(incidentId, "incidentId"))
                .orElseThrow(IncidentUnavailableException::new);
    }

    CommentResult addComment(String loginName, UUID incidentId, UUID requestId, String text) {
        requireText(requestId, text, MAX_COMMENT_LENGTH, InvalidCommentException::new);
        return incidents.addComment(requireLoginName(loginName), Objects.requireNonNull(incidentId, "incidentId"),
                requestId, text);
    }

    MutationResult assign(String loginName, UUID incidentId, long expectedVersion, UUID responderId) {
        if (expectedVersion < 0 || responderId == null) throw new InvalidMutationException();
        return incidents.assign(requireLoginName(loginName), Objects.requireNonNull(incidentId, "incidentId"),
                expectedVersion, responderId);
    }

    MutationResult unassign(String loginName, UUID incidentId, long expectedVersion) {
        if (expectedVersion < 0) throw new InvalidMutationException();
        return incidents.unassign(requireLoginName(loginName), Objects.requireNonNull(incidentId, "incidentId"),
                expectedVersion);
    }

    ResolutionResult resolve(
            String loginName, UUID incidentId, long expectedVersion, UUID requestId, String remarks) {
        if (expectedVersion < 0) throw new InvalidResolutionException();
        requireText(requestId, remarks, MAX_RESOLUTION_REMARKS_LENGTH, InvalidResolutionException::new);
        return incidents.resolve(requireLoginName(loginName), Objects.requireNonNull(incidentId, "incidentId"),
                expectedVersion, requestId, remarks);
    }

    private static String requireLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) throw new IncidentUnavailableException();
        return loginName;
    }

    private static void requireText(UUID requestId, String text, int limit,
            java.util.function.Supplier<? extends RuntimeException> invalid) {
        if (requestId == null || text == null || text.isBlank() || text.codePointCount(0, text.length()) > limit) {
            throw invalid.get();
        }
    }

    record IncidentDetail(
            UUID id,
            long version,
            String title,
            String description,
            String category,
            String status,
            boolean anonymous,
            String reporterLabel,
            String assigneeLabel,
            Instant createdAt,
            Instant submittedAt,
            Instant withdrawnAt,
            Instant queueEnteredAt,
            Instant firstAssignedAt,
            Instant latestAssignedAt,
            SloProgress slo,
            List<ResolutionEntry> resolutionHistory,
            List<CommentEntry> comments,
            List<AttachmentEntry> attachments,
            List<EligibleResponder> eligibleResponders) {
        IncidentDetail {
            resolutionHistory = List.copyOf(resolutionHistory);
            comments = List.copyOf(comments);
            attachments = List.copyOf(attachments);
            eligibleResponders = List.copyOf(eligibleResponders);
        }
    }

    record SloProgress(
            Long timeToClaimTargetMinutes,
            Long timeInProgressTargetMinutes,
            Long timeToClaimElapsedMillis,
            Long timeInProgressElapsedMillis,
            String claimState,
            String progressState) { }

    record ResolutionEntry(int cycleNumber, Instant resolvedAt, String resolvedByLabel, String remarks) { }
    record CommentEntry(UUID id, String authorLabel, String authorRole, String text, Instant createdAt) { }
    record AttachmentEntry(UUID id, String displayName, String mediaType, long sizeBytes, Instant createdAt) { }
    record EligibleResponder(UUID id, String loginName) { }

    record CommentResult(boolean added, UUID commentId) {
        static CommentResult conflict() { return new CommentResult(false, null); }
    }

    record MutationResult(boolean changed, long version) {
        static MutationResult conflict() { return new MutationResult(false, -1); }
    }

    record ResolutionResult(boolean resolved, long version, int cycleNumber) {
        static ResolutionResult conflict() { return new ResolutionResult(false, -1, 0); }
    }

    static final class IncidentUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class IncidentDataAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        IncidentDataAccessException(Throwable cause) { super(cause); }
    }

    static final class InvalidCommentException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class InvalidMutationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class InvalidResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
