package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.intake.command.UploadContent;

import java.io.InputStream;
import java.util.Optional;

/**
 * Task 0063 (R3): opens a password-protected PDF with a password sent through the API. The password is used for this
 * one call and kept nowhere: not stored, logged, audited or put in an answer or an exception.
 */
public interface PdfDecryptionPort {

    /**
     * The same PDF without protection, or empty when the PDF has none (the password is then not used).
     *
     * @throws com.securedoc.platform.application.exception.ValidationException LV-INT-036 when the password doesn't open
     *         it, LV-INT-008 when it can't be opened for another reason
     */
    Optional<DecryptedPdf> decrypt(InputStream content, char[] password);

    /** The decrypted copy; closing it deletes it. */
    interface DecryptedPdf extends UploadContent, AutoCloseable {
        @Override
        void close();
    }
}
