package com.securedoc.platform.domain.document;

/**
 * Classification of document package intake source.
 */
public enum PackageType {

    /** Manually uploaded via UI or API. */
    MANUAL_UPLOAD,

    /** Ingested from a watched/shared folder. */
    FOLDER_INTAKE,

    /** Submitted via external API integration. */
    API_SUBMISSION
}
