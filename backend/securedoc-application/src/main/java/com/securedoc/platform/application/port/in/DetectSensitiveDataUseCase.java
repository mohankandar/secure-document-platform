package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;

/**
 * Inbound port for running sensitive-data detection on a document.
 * Reads the EXTRACTED_TEXT artifact, runs deterministic detection rules,
 * creates Finding records, evaluates policy, and transitions the document
 * to its next state.
 */
public interface DetectSensitiveDataUseCase {

    DetectSensitiveDataResult execute(DetectSensitiveDataCommand command);
}
