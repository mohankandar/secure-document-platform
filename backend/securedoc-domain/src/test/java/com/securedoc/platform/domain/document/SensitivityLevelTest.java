package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intake.ExternalManifestDocument;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.securedoc.platform.domain.document.SensitivityLevel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 0021: reading a level as sent, and the one effective-level rule. */
class SensitivityLevelTest {

    @Test
    void nothingSentIsStandardWithoutWarning() {
        assertEquals(new Received(STANDARD, false), fromSource(null));
    }

    @Test
    void eachExactNameIsKept() {
        for (SensitivityLevel level : values()) {
            assertEquals(new Received(level, false), fromSource(level.name()), level.name());
        }
    }

    @Test
    void anUnknownOrWronglyCasedValueIsTakenAsStandardWithTheWarning() {
        for (String sent : List.of("confidential", "Confidential", "SECRET", "HIGHLY CONFIDENTIAL", " CONFIDENTIAL",
                "CONFIDENTIAL ", "", "   ", "1", "{}", "null")) {
            Received received = fromSource(sent);
            assertEquals(STANDARD, received.level(), sent);
            assertTrue(received.unknown(), sent);
        }
        assertEquals("LV-INT-005", UNKNOWN_LEVEL_WARNING);
        assertEquals("Unknown sensitivity level, taken as Standard", UNKNOWN_LEVEL_WARNING_TEXT);
    }

    @Test
    void effectiveLevelIsTheHigherOfDocumentAndCaseForEveryPair() {
        for (SensitivityLevel document : values()) {
            for (SensitivityLevel kase : values()) {
                SensitivityLevel expected = document.ordinal() >= kase.ordinal() ? document : kase;
                assertEquals(expected, effective(document, kase), document + "/" + kase);
            }
        }
        assertEquals(CONFIDENTIAL, effective(CONFIDENTIAL, null));
        assertEquals(CONFIDENTIAL, effective(null, CONFIDENTIAL));
        assertEquals(STANDARD, effective(null, null));
    }

    @Test
    void theOrderIsStandardConfidentialHighlyConfidential() {
        assertEquals(List.of(STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL), List.of(values()));
    }

    @Test
    void aCaseIsAsHighAsItsHighestDocument() {
        assertEquals(STANDARD, effectiveForCase(STANDARD, List.of()));
        assertEquals(CONFIDENTIAL, effectiveForCase(CONFIDENTIAL, List.of(STANDARD)));
        assertEquals(HIGHLY_CONFIDENTIAL, effectiveForCase(STANDARD, List.of(STANDARD, HIGHLY_CONFIDENTIAL)));
        assertEquals(HIGHLY_CONFIDENTIAL, effectiveForCase(HIGHLY_CONFIDENTIAL, List.of(CONFIDENTIAL)));
    }

    @Test
    void packageAndDocumentKeepTheLevelTheyWereGiven() {
        TenantId tenant = TenantId.generate();
        DocumentPackage pkg = DocumentPackage.createIntake(tenant, PackageType.API_SUBMISSION, null, null, null,
                ActorId.generate(), PackageMetadata.empty(), CONFIDENTIAL);
        assertEquals(CONFIDENTIAL, pkg.sensitivityLevel());
        Document doc = Document.create(tenant, pkg.id(), FileName.of("a.pdf"), MimeType.of("application/pdf"), 1,
                Checksum.sha256("a".repeat(64)), DocumentType.PDF, HIGHLY_CONFIDENTIAL);
        pkg.addDocument(doc);
        assertEquals(HIGHLY_CONFIDENTIAL, doc.sensitivityLevel());
        assertEquals(HIGHLY_CONFIDENTIAL, doc.effectiveSensitivityLevel(pkg.sensitivityLevel()));
        assertEquals(HIGHLY_CONFIDENTIAL, pkg.effectiveSensitivityLevel());

        DocumentPackage legacy = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        assertEquals(STANDARD, legacy.sensitivityLevel(), "nothing sent means Standard");
        Document reloaded = Document.reconstitute(doc.id(), tenant, pkg.id(), doc.fileName(), doc.mimeType(), 1,
                doc.checksum(), DocumentType.PDF, Instant.now(), DocumentStatus.UPLOADED, List.of(), Instant.now(),
                HIGHLY_CONFIDENTIAL);
        assertEquals(HIGHLY_CONFIDENTIAL, reloaded.sensitivityLevel());
    }

    @Test
    void anExpectedDocumentKeepsItsLevelWhenReceived() {
        ExternalManifestDocument expected = ExternalManifestDocument.expected("DOC-1", "a.pdf", null, CONFIDENTIAL);
        ExternalManifestDocument received = expected.markReceived(DocumentId.generate(), "a".repeat(64), "key-1",
                Instant.now());
        assertEquals(CONFIDENTIAL, received.sensitivityLevel());
        assertEquals(STANDARD, ExternalManifestDocument.expected("DOC-2", "b.pdf", null).sensitivityLevel());
    }
}
