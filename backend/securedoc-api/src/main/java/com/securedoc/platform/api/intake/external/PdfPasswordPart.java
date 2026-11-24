package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Task 0063 (R3): the PDF password of an upload, from the multipart part {@code password} only. Never from the address
 * (addresses are logged) and never over plain HTTP. The part stays in memory: parts up to
 * spring.servlet.multipart.file-size-threshold (4 KB) are not written to a file, and a longer one is refused. The caller
 * clears the characters after use.
 */
final class PdfPasswordPart {

    static final String NAME = "password";
    /** PDF passwords are at most 127 bytes (AES-256). */
    static final int MAX_BYTES = 127;

    private PdfPasswordPart() {
    }

    /** The password, or null when none was sent. */
    static char[] read(HttpServletRequest request) {
        if (inAddress(request.getQueryString())) throw wrongWay();
        Part part;
        try {
            part = request.getPart(NAME);
        } catch (IOException | ServletException e) {
            // The cause is left out: it could repeat what was sent.
            throw new ValidationException(LvErrorCode.INT_020, "The upload could not be read");
        }
        if (part == null || part.getSize() == 0) return null;
        if (!encryptedConnection(request)) throw wrongWay();
        if (part.getSize() > MAX_BYTES) throw notAccepted();
        byte[] bytes = null;
        try (var in = part.getInputStream()) {
            bytes = in.readNBytes(MAX_BYTES + 1);
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
            char[] password = new char[chars.remaining()];
            chars.get(password);
            if (chars.hasArray()) Arrays.fill(chars.array(), '\0');
            return password;
        } catch (IOException e) {
            throw new ValidationException(LvErrorCode.INT_020, "The upload could not be read");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean inAddress(String query) {
        if (query == null) return false;
        for (String pair : query.split("&")) {
            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            try {
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException undecodable) {
                // Compared as sent.
            }
            if (NAME.equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    /** HTTPS to the app, HTTPS to the proxy in front of it, or a call from this same machine (local development). */
    private static boolean encryptedConnection(HttpServletRequest request) {
        if (request.isSecure()) return true;
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if (forwarded != null) return "https".equalsIgnoreCase(forwarded.split(",")[0].trim());
        String from = request.getRemoteAddr();
        return "127.0.0.1".equals(from) || "0:0:0:0:0:0:0:1".equals(from) || "::1".equals(from);
    }

    private static ValidationException wrongWay() {
        return new ValidationException(LvErrorCode.INT_037,
                "Send the PDF password in the request body, over HTTPS");
    }

    private static ValidationException notAccepted() {
        return new ValidationException(LvErrorCode.INT_036, "The password does not open this PDF");
    }
}
