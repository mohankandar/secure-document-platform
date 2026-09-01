package com.securedoc.platform.api.intake;

import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Batch2MultiDocumentIntakeContractTest {

    @Test
    void multiplePdfsRemainSeparateAndDoNotStartProcessingBeforeExplicitSubmit() {
        TenantId tenantId = TenantId.generate();
        DocumentPackage intake = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(), PackageMetadata.empty());

        List<Document> documents = List.of(
                pdf(tenantId, intake, "Contract.pdf", "contract-checksum"),
                pdf(tenantId, intake, "CreditDisclosure.pdf", "credit-checksum"),
                pdf(tenantId, intake, "Application.pdf", "application-checksum"));

        documents.forEach(intake::addDocument);

        assertThat(intake.status()).isEqualTo(PackageStatus.CREATED);
        assertThat(intake.documentCount()).isEqualTo(3);
        assertThat(intake.documents())
                .extracting(document -> document.fileName().value())
                .containsExactly("Contract.pdf", "CreditDisclosure.pdf", "Application.pdf");
        assertThat(intake.documents()).allMatch(document -> document.status().name().equals("UPLOADED"));

        intake.submit();

        assertThat(intake.status()).isEqualTo(PackageStatus.SUBMITTED);
        assertThat(intake.documentCount()).isEqualTo(3);
    }

    private Document pdf(TenantId tenantId, DocumentPackage intake, String fileName, String checksum) {
        return Document.create(
                tenantId,
                intake.id(),
                FileName.of(fileName),
                MimeType.pdf(),
                1024,
                Checksum.sha256(checksum),
                DocumentType.PDF);
    }
}
