package com.securedoc.platform.api.dto;

import java.util.List;
import java.util.UUID;

public record UpdateBusinessIdentifiersRequest(List<Item> identifiers) {
    public record Item(
            UUID id,
            String label,
            boolean required,
            boolean primary,
            boolean searchable,
            boolean extractable,
            boolean displayable
    ) {}
}
