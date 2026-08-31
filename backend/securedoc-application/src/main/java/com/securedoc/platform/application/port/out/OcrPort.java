package com.securedoc.platform.application.port.out;

import java.util.List;

/**
 * Provider-neutral OCR boundary for one rendered page image.
 * OCR is responsible only for recognizing text and page geometry; it does not
 * classify sensitive content and it never performs redaction.
 */
public interface OcrPort {

    OcrPageOutput recognize(OcrPageInput input);

    record OcrPageInput(
            int pageNumber,
            byte[] imageBytes,
            String imageMimeType,
            int pixelWidth,
            int pixelHeight
    ) {
        public OcrPageInput {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IllegalArgumentException("OCR image bytes must not be empty");
            }
            if (imageMimeType == null || imageMimeType.isBlank()) {
                throw new IllegalArgumentException("OCR image MIME type is required");
            }
            if (pixelWidth <= 0 || pixelHeight <= 0) {
                throw new IllegalArgumentException("OCR image dimensions must be positive");
            }
            imageBytes = imageBytes.clone();
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes.clone();
        }
    }

    record OcrPageOutput(
            String text,
            List<OcrTextSpan> spans,
            double confidence
    ) {
        public OcrPageOutput {
            if (text == null) {
                text = "";
            }
            if (spans == null) {
                spans = List.of();
            } else {
                spans = List.copyOf(spans);
            }
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("OCR confidence must be between 0.0 and 1.0");
            }
        }
    }

    record OcrTextSpan(
            int startOffset,
            int endOffset,
            double left,
            double top,
            double width,
            double height,
            double confidence
    ) {
        public OcrTextSpan {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("Invalid OCR span offsets");
            }
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("OCR span confidence must be between 0.0 and 1.0");
            }
        }
    }
}
