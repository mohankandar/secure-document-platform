package com.securedoc.platform.application.operations.model;

public record DashboardOverview(
        long total,
        long processing,
        long needsReview,
        long needsApproval,
        long exported,
        long technicalAttention,
        long rejected
) {}
