package com.securedoc.platform.scanbridge.model;

/** Durable Scanner Intake lifecycle owned by the local bridge. */
public enum ScanSessionStatus {
    WAITING_FOR_SCAN,
    READY,
    QUEUED,
    SUBMITTING,
    SUBMITTED,
    ATTENTION_REQUIRED,
    CANCELLED
}
