package com.securedoc.platform.infrastructure.resilience;

import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.resilience.ResilientOcrPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Spring edge wrapper that applies selective retry to the local OCR adapter. */
@Component
@Primary
public class ResilientOcrAdapter implements OcrPort {

    private final ResilientOcrPort delegate;

    public ResilientOcrAdapter(TesseractOcrAdapter tesseract,
                               TechnicalResilienceProperties properties) {
        this.delegate = new ResilientOcrPort(
                tesseract,
                new SelectiveRetryExecutor(
                        Math.max(1, properties.getMaxAttempts()),
                        Duration.ofMillis(Math.max(0L, properties.getInitialBackoffMs()))));
    }

    @Override
    public OcrPageOutput recognize(OcrPageInput input) {
        return delegate.recognize(input);
    }
}
