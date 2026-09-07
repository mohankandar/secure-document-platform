package com.securedoc.platform.application.support.model;

import java.util.List;

public record SupportIncidentSearchPage(
        List<SupportIncidentSummary> items,
        long total,
        int page,
        int size) {

    public SupportIncidentSearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
