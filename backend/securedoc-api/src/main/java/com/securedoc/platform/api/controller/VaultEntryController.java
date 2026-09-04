package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.RegisterVaultEntryUseCase;
import com.securedoc.platform.application.vault.command.RegisterVaultEntryCommand;
import com.securedoc.platform.application.vault.result.RegisterVaultEntryResult;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "LOCAL")
@RequestMapping("/api/v1/vault/entries")
public class VaultEntryController {

    private final RegisterVaultEntryUseCase registerVaultEntryUseCase;
    private final RequestContextResolver contextResolver;

    public VaultEntryController(RegisterVaultEntryUseCase registerVaultEntryUseCase,
                                 RequestContextResolver contextResolver) {
        this.registerVaultEntryUseCase = registerVaultEntryUseCase;
        this.contextResolver = contextResolver;
    }

    @PostMapping
    @Transactional
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Map<String, Object>> registerVaultEntry(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);

        PackageId packageId = PackageId.of(UUID.fromString(body.get("packageId")));
        DocumentId documentId = DocumentId.of(UUID.fromString(body.get("documentId")));
        ArtifactId artifactId = ArtifactId.of(UUID.fromString(body.get("artifactId")));

        RegisterVaultEntryCommand command = RegisterVaultEntryCommand.of(
                principal.tenantId(), packageId, documentId, artifactId,
                principal.actorId(), correlationId);

        RegisterVaultEntryResult result = registerVaultEntryUseCase.execute(command);

        return ResponseEntity.ok(Map.of(
                "vaultEntryId", result.id().toString(),
                "packageId", result.packageId().toString(),
                "documentId", result.documentId().toString(),
                "artifactId", result.artifactId().toString(),
                "artifactType", result.artifactType().name(),
                "registeredAt", result.registeredAt().toString()));
    }
}
