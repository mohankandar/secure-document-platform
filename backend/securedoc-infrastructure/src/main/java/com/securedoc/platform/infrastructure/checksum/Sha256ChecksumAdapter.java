package com.securedoc.platform.infrastructure.checksum;

import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.domain.vo.Checksum;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 checksum implementation.
 * Streams content in chunks to avoid loading large files fully into memory.
 */
@Component
public class Sha256ChecksumAdapter implements ChecksumPort {

    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    @Override
    public Checksum compute(InputStream content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = content.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            String hexValue = HexFormat.of().formatHex(digest.digest());
            return Checksum.sha256(hexValue);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute checksum", e);
        }
    }
}
