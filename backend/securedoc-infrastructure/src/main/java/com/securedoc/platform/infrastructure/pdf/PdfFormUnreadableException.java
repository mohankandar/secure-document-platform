package com.securedoc.platform.infrastructure.pdf;

import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;

/** Task 0053: the document is a form LexaVault can't read (XFA only), {@code LV-PRC-010}. The message is fixed. */
public class PdfFormUnreadableException extends IllegalStateException implements LvCoded {

    public PdfFormUnreadableException() {
        super("PDF form type can't be read");
    }

    @Override
    public LvErrorCode lvCode() {
        return LvErrorCode.PRC_010;
    }
}
