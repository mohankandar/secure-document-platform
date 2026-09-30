package com.documentplatform.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class StableIdentityId {
    private StableIdentityId() {}

    public static UUID fromIssuerAndSubject(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("issuer and subject are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((issuer.trim() + "|" + subject.trim()).getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            long msb = buffer.getLong();
            long lsb = buffer.getLong();
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L;
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
