package com.securedoc.platform.domain.review;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Entity representing a human review task for a document package.
 * Tracks assignment, progress, feedback, and final decision.
 */
public final class ReviewTask {

    private final ReviewTaskId id;
    private final TenantId tenantId;
    private final PackageId packageId;
    private final Instant createdAt;
    private ReviewStatus status;
    private ActorId assignedTo;
    private final List<FeedbackRecord> feedback;
    private Instant updatedAt;

    private ReviewTask(ReviewTaskId id, TenantId tenantId, PackageId packageId, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.packageId = packageId;
        this.createdAt = createdAt;
        this.status = ReviewStatus.PENDING;
        this.feedback = new ArrayList<>();
        this.updatedAt = createdAt;
    }

    public static ReviewTask create(TenantId tenantId, PackageId packageId) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");

        return new ReviewTask(ReviewTaskId.generate(), tenantId, packageId, Instant.now());
    }

    public static ReviewTask reconstitute(ReviewTaskId id, TenantId tenantId, PackageId packageId,
                                           Instant createdAt, ReviewStatus status, ActorId assignedTo,
                                           List<FeedbackRecord> feedback, Instant updatedAt) {
        ReviewTask task = new ReviewTask(id, tenantId, packageId, createdAt);
        task.status = status;
        task.assignedTo = assignedTo;
        task.feedback.addAll(feedback);
        task.updatedAt = updatedAt;
        return task;
    }

    public void assign(ActorId reviewerId) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        if (status != ReviewStatus.PENDING && status != ReviewStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot assign task in status: " + status);
        }
        this.assignedTo = reviewerId;
        this.status = ReviewStatus.ASSIGNED;
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.ASSIGN, null));
        updatedAt = Instant.now();
    }

    public void start(ActorId reviewerId) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        if (status != ReviewStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot start task in status: " + status);
        }
        if (!reviewerId.equals(assignedTo)) {
            throw new IllegalStateException("Only the assigned reviewer can start the task");
        }
        this.status = ReviewStatus.IN_PROGRESS;
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.START, null));
        updatedAt = Instant.now();
    }

    public void approve(ActorId reviewerId, String comment) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        if (status != ReviewStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot approve task in status: " + status);
        }
        if (!reviewerId.equals(assignedTo)) {
            throw new IllegalStateException("Only the assigned reviewer can approve the task");
        }
        this.status = ReviewStatus.APPROVED;
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.APPROVE, comment));
        updatedAt = Instant.now();
    }

    public void reject(ActorId reviewerId, String comment) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        if (status != ReviewStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot reject task in status: " + status);
        }
        this.status = ReviewStatus.REJECTED;
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.REJECT, comment));
        updatedAt = Instant.now();
    }

    public void escalate(ActorId reviewerId, String reason) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(reason, "Escalation reason must not be null");
        if (status != ReviewStatus.IN_PROGRESS && status != ReviewStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot escalate task in status: " + status);
        }
        this.status = ReviewStatus.ESCALATED;
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.ESCALATE, reason));
        updatedAt = Instant.now();
    }

    public void addComment(ActorId reviewerId, String comment) {
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(comment, "Comment must not be null");
        if (status == ReviewStatus.CANCELLED) {
            throw new IllegalStateException("Cannot add comments to cancelled task");
        }
        feedback.add(FeedbackRecord.create(reviewerId, ReviewAction.COMMENT, comment));
        updatedAt = Instant.now();
    }

    public void cancel(ActorId actorId) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status == ReviewStatus.APPROVED || status == ReviewStatus.REJECTED) {
            throw new IllegalStateException("Cannot cancel completed task in status: " + status);
        }
        this.status = ReviewStatus.CANCELLED;
        feedback.add(FeedbackRecord.create(actorId, ReviewAction.CANCEL, null));
        updatedAt = Instant.now();
    }

    // --- Accessors ---

    public ReviewTaskId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PackageId packageId() {
        return packageId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ReviewStatus status() {
        return status;
    }

    public Optional<ActorId> assignedTo() {
        return Optional.ofNullable(assignedTo);
    }

    public List<FeedbackRecord> feedback() {
        return Collections.unmodifiableList(feedback);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isComplete() {
        return status == ReviewStatus.APPROVED || status == ReviewStatus.REJECTED;
    }
}
