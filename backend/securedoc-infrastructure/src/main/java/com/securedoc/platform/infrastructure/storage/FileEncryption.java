package com.securedoc.platform.infrastructure.storage;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Task 0060: every stored file is encrypted with the customer's keys as it is written, and decrypted as it is read, in
 * memory, 64 KiB at a time. No plain copy is ever written. The development-only mode writes plain files; what is already
 * encrypted stays closed there.
 */
public final class FileEncryption {
    private final StreamCipher cipher;
    private final CustomerId customer;

    private FileEncryption(StreamCipher cipher, CustomerId customer) {
        this.cipher = cipher;
        this.customer = customer;
    }

    public static FileEncryption with(StreamCipher cipher, CustomerId customer) {
        return new FileEncryption(Objects.requireNonNull(cipher), Objects.requireNonNull(customer));
    }

    /** LOCAL development builds only (see StorageEncryptionConfig): files are written plain and recorded as plain. */
    public static FileEncryption developmentPlain() {
        return new FileEncryption(null, null);
    }

    public boolean encrypts() {
        return cipher != null;
    }

    /** Writes the content to the file. Null: it was written plain (development only). A failure leaves no file. */
    EnvelopeInfo write(InputStream content, Path file) throws IOException {
        boolean written = false;
        try (OutputStream out = Files.newOutputStream(file)) {
            EnvelopeInfo envelope = null;
            if (cipher == null) {
                content.transferTo(out);
            } else {
                try {
                    envelope = cipher.encrypt(customer, content, out);
                } catch (CryptoException refused) {
                    throw failure(refused);
                }
            }
            written = true;
            return envelope;
        } finally {
            if (!written) Files.deleteIfExists(file);
        }
    }

    /**
     * The plain content of an encrypted file. Before the first byte is given out the whole file is compared with its
     * record (size and SHA-256), so a changed, cut or exchanged file is refused as a whole.
     */
    InputStream read(EnvelopeInfo envelope, Path file) throws IOException {
        if (cipher == null) {
            throw new TechnicalDependencyException(TechnicalDependency.KEY_SERVICE, TechnicalFailureCode.KEY_SERVICE_REFUSED,
                    false, "The stored file is encrypted and no key service is set up");
        }
        if (Files.size(file) != envelope.encryptedBytes() || !sha256(file).equals(envelope.ciphertextSha256())) {
            throw refused("Stored file differs from its record");
        }
        try {
            return DecryptingInputStream.open(out -> {
                try (InputStream in = Files.newInputStream(file)) {
                    cipher.decrypt(customer, envelope, in, out);
                }
            });
        } catch (CryptoException refused) {
            throw failure(refused);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static TechnicalDependencyException refused(String safeMessage) {
        return new TechnicalDependencyException(TechnicalDependency.STORAGE, TechnicalFailureCode.STORED_FILE_REFUSED,
                false, safeMessage);
    }

    /** The key service's code decides: down, refused, or the file itself. The exception holds ids and codes only. */
    static TechnicalDependencyException failure(CryptoException e) {
        return switch (e.code()) {
            case CRYPTO_KEY_SERVICE_UNREACHABLE, CRYPTO_KEY_SERVICE_TLS, CRYPTO_KEY_SERVICE_SEALED, CRYPTO_KEY_SERVICE_ERROR ->
                    keyService(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, e);
            case CRYPTO_CIPHERTEXT_INVALID, CRYPTO_FORMAT_UNKNOWN, CRYPTO_WRONG_CUSTOMER, CRYPTO_KEY_SERVICE_MISMATCH ->
                    new TechnicalDependencyException(TechnicalDependency.STORAGE, TechnicalFailureCode.STORED_FILE_REFUSED,
                            false, "Stored file was not opened: " + e.code().name(), e);
            default -> keyService(TechnicalFailureCode.KEY_SERVICE_REFUSED, e);
        };
    }

    private static TechnicalDependencyException keyService(TechnicalFailureCode code, CryptoException e) {
        return new TechnicalDependencyException(TechnicalDependency.KEY_SERVICE, code, true,
                "Key service: " + e.code().name(), e);
    }
}
