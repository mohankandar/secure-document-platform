package com.securedoc.platform.api.dto;

public record DashboardIdentifierResponse(String key, String label, String value, boolean primary, int displayOrder) {}
