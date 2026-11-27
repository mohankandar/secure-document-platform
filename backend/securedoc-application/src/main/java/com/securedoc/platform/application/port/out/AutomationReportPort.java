package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;

/** Task 0105: counts of one entity's redacted findings, by who placed them. Counts only, never a value. */
public interface AutomationReportPort {

    /**
     * @param automatic      found and placed by LexaVault, with no person
     * @param placedByPerson found by LexaVault, but a person drew or moved its box
     * @param drawnByPerson  missed by LexaVault, added by a person
     * @param byText         of all, found by the page's text (PDF text, OCR, form fields)
     * @param byVision       of all, found by the vision model
     */
    record Counts(long automatic, long placedByPerson, long drawnByPerson, long byText, long byVision) { }

    /** The findings searched in [from, to) that were redacted or are confirmed to be. */
    Counts counts(TenantId entity, Instant from, Instant to);
}
