package com.securedoc.platform.api.dto;

public record DashboardOverviewResponse(long total, long processing, long needsReview, long needsApproval,
                                        long exported, long technicalAttention, long rejected) {}
