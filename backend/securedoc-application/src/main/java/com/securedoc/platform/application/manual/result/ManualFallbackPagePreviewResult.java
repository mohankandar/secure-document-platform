package com.securedoc.platform.application.manual.result;

public record ManualFallbackPagePreviewResult(byte[] content, String mimeType, int pageNumber,
                                               int pixelWidth, int pixelHeight) {
    public ManualFallbackPagePreviewResult {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
