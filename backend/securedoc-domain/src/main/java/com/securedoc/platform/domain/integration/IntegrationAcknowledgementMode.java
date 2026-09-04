package com.securedoc.platform.domain.integration;

/** Common acknowledgement contract. Concrete connectors define the protocol-specific evidence. */
public enum IntegrationAcknowledgementMode {
    NONE,
    HTTP_SUCCESS,
    DESTINATION_RECEIPT,
    DURABLE_FILE_WRITE,
    SOURCE_PICKUP_ACK,
    SDV_SUBMISSION_ACK
}
