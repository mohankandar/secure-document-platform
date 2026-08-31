package com.securedoc.platform.application.operations.model;

/** Tenant-level Business Admin policy for a dashboard column. */
public record DashboardColumnSetting(
        String key,
        boolean allowed,
        boolean defaultVisible,
        int displayOrder
) {}
