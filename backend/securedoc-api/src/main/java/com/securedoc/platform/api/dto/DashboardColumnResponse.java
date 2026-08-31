package com.securedoc.platform.api.dto;

public record DashboardColumnResponse(String key, String label, String kind, boolean primary,
                                      boolean searchable, boolean defaultVisible, int displayOrder) {}
