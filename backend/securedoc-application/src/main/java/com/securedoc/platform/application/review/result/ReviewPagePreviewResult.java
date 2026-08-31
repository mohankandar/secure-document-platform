package com.securedoc.platform.application.review.result;

/**
 * Rendered image of one source PDF page used only inside the Manual Review workbench.
 */
public record ReviewPagePreviewResult(
        byte[] content,
        String mimeType,
        int pageNumber,
        int pixelWidth,
        int pixelHeight
) {
    public ReviewPagePreviewResult {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
