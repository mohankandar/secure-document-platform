package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 (B4, #200, #201): the delivery record names no file and holds no absolute path. */
class LocalFolderDeliveryAdapterTest {

    @Test
    void theRecordedLocationHasIdsOnlyWhileTheFileKeepsItsNameOnDisk(@TempDir Path root) throws Exception {
        LocalDeliveryProperties properties = new LocalDeliveryProperties();
        properties.setOutputPath(root.toString());
        LocalFolderDeliveryAdapter adapter = new LocalFolderDeliveryAdapter(properties);
        TenantId tenant = TenantId.generate();
        PackageId packageId = PackageId.generate();
        DocumentId documentId = DocumentId.generate();
        byte[] body = "PDF".getBytes(StandardCharsets.UTF_8);
        DeliveryPort.DeliveryItem item = new DeliveryPort.DeliveryItem(documentId, "doc-1", "MARKER-borrower-name.pdf",
                () -> new ByteArrayInputStream(body), body.length, "application/pdf", null, null);

        DeliveryPort.BatchDeliveryReference result = adapter.deliverBatch(new DeliveryPort.DeliveryBatch(tenant, packageId, 1,
                "sub-1", null, Map.of(), Map.of(), List.of(item), null));

        String location = result.documents().get(0).location();
        assertEquals("local-folder:" + packageId.value() + "/cycle-1/" + documentId.value(), location);
        assertFalse(location.contains("MARKER"), location);
        assertFalse(location.contains(root.toString()), location);
        // The delivered file itself is unchanged: the destination still receives the original name.
        Path delivered = root.resolve(tenant.value().toString()).resolve(packageId.value().toString())
                .resolve("cycle-1").resolve(documentId.value().toString()).resolve("MARKER-borrower-name.pdf");
        assertArrayEquals(body, Files.readAllBytes(delivered));
    }
}
