package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.port.out.PdfFileIdentityPort.PdfIdentity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0104: a PDF's permanent /ID and its XMP DocumentID, as hashes; a re-saved file keeps its /ID's first part. */
class PdfBoxFileIdentityAdapterTest {

    private final PdfBoxFileIdentityAdapter adapter = new PdfBoxFileIdentityAdapter();

    @Test
    void theTrailerIdsFirstPartIsKeptWhenTheFileIsSavedAgain() throws Exception {
        byte[] first = pdf("permanent-1", "changing-1", null);
        byte[] resaved = pdf("permanent-1", "changing-2", null);
        PdfIdentity a = adapter.read(new ByteArrayInputStream(first));
        PdfIdentity b = adapter.read(new ByteArrayInputStream(resaved));
        assertNotNull(a.pdfIdHash());
        assertEquals(a.pdfIdHash(), b.pdfIdHash(), "only the first part identifies the document");
        assertNotEquals(a.pdfIdHash(), adapter.read(new ByteArrayInputStream(pdf("permanent-2", "changing-1", null))).pdfIdHash());
        assertFalse(a.pdfIdHash().contains("permanent"), "a hash, never the id itself");
        assertEquals(64, a.pdfIdHash().length());
    }

    @Test
    void theXmpDocumentIdIsReadInAttributeAndElementForm() throws Exception {
        String attribute = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description xmlns:xmpMM='http://ns.adobe.com/xap/1.0/mm/' xmpMM:DocumentID='uuid:abc-123'/></rdf:RDF></x:xmpmeta>";
        String element = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description xmlns:xmpMM='http://ns.adobe.com/xap/1.0/mm/'><xmpMM:DocumentID>uuid:abc-123</xmpMM:DocumentID>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        String a = adapter.read(new ByteArrayInputStream(pdf(null, null, attribute))).xmpDocumentIdHash();
        String b = adapter.read(new ByteArrayInputStream(pdf(null, null, element))).xmpDocumentIdHash();
        assertNotNull(a);
        assertEquals(a, b);
    }

    @Test
    void aFileWithoutIdsOrNotReadableHasNone() throws Exception {
        PdfIdentity none = adapter.read(new ByteArrayInputStream(pdfWithoutTrailerId()));
        assertNull(none.xmpDocumentIdHash());
        assertEquals(PdfIdentity.NONE, adapter.read(new ByteArrayInputStream("not a pdf".getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] pdf(String permanentId, String changingId, String xmp) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (permanentId != null) {
                COSArray id = new COSArray();
                id.add(new COSString(permanentId.getBytes(StandardCharsets.US_ASCII)));
                id.add(new COSString(changingId.getBytes(StandardCharsets.US_ASCII)));
                document.getDocument().setDocumentID(id);
            }
            if (xmp != null) {
                PDMetadata metadata = new PDMetadata(document);
                metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
                document.getDocumentCatalog().setMetadata(metadata);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfWithoutTrailerId() throws Exception {
        byte[] bytes = pdf(null, null, null);
        try (PDDocument d = Loader.loadPDF(bytes)) {
            assertNotNull(d); // readable; PDFBox may add an /ID of its own on save, which is fine
        }
        return bytes;
    }
}
