package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.port.out.PdfFileIdentityPort;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0104: reads a PDF's permanent id (first part of the trailer {@code /ID}, which a re-save keeps) and its XMP
 * {@code xmpMM:DocumentID}, and gives them as SHA-256 hashes. No text of the document is read. A file it can't open has
 * no identity ({@link PdfIdentity#NONE}); the intake's own checks refuse such a file, not this.
 */
@Component
public class PdfBoxFileIdentityAdapter implements PdfFileIdentityPort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxFileIdentityAdapter.class);
    /** {@code xmpMM:DocumentID="..."} or {@code <xmpMM:DocumentID>...</xmpMM:DocumentID>}. */
    private static final Pattern XMP_DOCUMENT_ID = Pattern.compile(
            "xmpMM:DocumentID\\s*=\\s*[\"']([^\"']{1,512})[\"']|<xmpMM:DocumentID>\\s*([^<]{1,512}?)\\s*</xmpMM:DocumentID>");

    @Override
    public PdfIdentity read(InputStream content) {
        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(content, "lexavault-identity-")) {
            PDDocument document = staged.document();
            return new PdfIdentity(trailerId(document), xmpDocumentId(document));
        } catch (IOException | RuntimeException e) {
            log.debug("PDF_IDENTITY_NOT_READ exceptionType={}", e.getClass().getSimpleName());
            return PdfIdentity.NONE;
        }
    }

    private static String trailerId(PDDocument document) {
        COSArray id = document.getDocument().getDocumentID();
        if (id == null || id.size() < 1 || !(id.getObject(0) instanceof COSString first) || first.getBytes().length == 0) return null;
        return sha256(first.getBytes());
    }

    private static String xmpDocumentId(PDDocument document) throws IOException {
        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata == null) return null;
        byte[] xml;
        try (InputStream in = metadata.exportXMPMetadata()) {
            xml = in.readNBytes(1024 * 1024);
        }
        Matcher m = XMP_DOCUMENT_ID.matcher(new String(xml, StandardCharsets.UTF_8));
        if (!m.find()) return null;
        String value = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
        return value.isEmpty() ? null : sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
