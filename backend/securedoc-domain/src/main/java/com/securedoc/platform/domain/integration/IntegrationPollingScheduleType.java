package com.securedoc.platform.domain.integration;

/** Supported V1 polling schedule shapes. The UI does not expose raw cron expressions. */
public enum IntegrationPollingScheduleType {
    INTERVAL,
    DAILY
}
