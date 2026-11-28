package com.securedoc.platform.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Hardening (task 0110): endpoints deny by default. An API method with no {@link RequireCapability} is refused, unless it carries
 * this mark: open to every signed-in caller, or to a machine caller (its own checks, if any, are inside it). Each one is listed in {@link #LISTED}; a test fails when the list and the marks differ.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenEndpoint {

    /** Why it is open to every signed-in caller. */
    String value();

    /** The open endpoints, as Controller.method, sorted. */
    List<String> LISTED = List.of(
            "ArtifactDownloadController.downloadRedacted",
            "BrandingController.current",
            "ComplianceReportController.create",
            "CurrentUserController.currentUser",
            "EnteredEntityController.entered",
            "ExternalIntakeController.create",
            "ExternalIntakeController.status",
            "ExternalIntakeController.uploadDocument",
            "OidcTenantDiscoveryController.list",
            "ScannerIntakeController.create",
            "ScannerIntakeController.status",
            "ScannerIntakeController.uploadDocument");
}
