package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.delivery.StagingFolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0097: erases what a killed server left of a delivery to a folder. The handler is there also when deliveries no
 * longer go to a folder, so a replay can always run: the configured location is then looked at if it is there. While they
 * do go to a folder, a location that is not there says nothing about its files and is a failure, never "gone".
 */
@Component
public class DeliveryLeftoverErasure {
    private final StoredFileErasure files;
    private final Path root;
    private final boolean deliversToFolder;

    public DeliveryLeftoverErasure(StoredFileErasure files, LocalDeliveryProperties properties,
                                   @Value("${securedoc.delivery.type:}") String deliveryType) {
        this.files = Objects.requireNonNull(files);
        this.root = Path.of(properties.getOutputPath()).toAbsolutePath().normalize();
        this.deliversToFolder = "local".equalsIgnoreCase(deliveryType);
    }

    /** Before anything is logged: an id of LexaVault's form, and a location that can be looked at. */
    void check(EraseRequest request) {
        EraseRecord.folderDelivery(request);
        StoredFileErasure.guarded(() -> staging(request));
    }

    EraseHandler.Applied erase(EraseEntry entry, EraseHandler.Context context) {
        EraseRequest request = entry.request();
        StagingFolder folder = EraseRecord.folderDelivery(request);
        return files.inTransaction(context, c -> {
            Optional<Path> staging = staging(request);
            boolean erased = staging.isPresent() && folder.erase(staging.get());
            StoredFileErasure.audit(c, entry, "FolderDelivery", folder.deliveryId().toString(), folder.packageId());
            return erased ? EraseHandler.Applied.ERASED : EraseHandler.Applied.ALREADY_GONE;
        });
    }

    /** One that is there came back with a backup of the location only; its erasure is finished here (as for stored files). */
    EraseHandler.Verified verify(EraseEntry entry) {
        StagingFolder folder = EraseRecord.folderDelivery(entry.request());
        return StoredFileErasure.guarded(() -> {
            Optional<Path> staging = staging(entry.request());
            if (staging.isEmpty()) return EraseHandler.Verified.GONE;
            folder.erase(staging.get());
            return folder.isIn(staging.get()) ? EraseHandler.Verified.READABLE : EraseHandler.Verified.GONE;
        });
    }

    private Optional<Path> staging(EraseRequest request) throws IOException {
        if (!Files.isDirectory(root)) {
            if (!deliversToFolder) return Optional.empty();
            throw new EraseHandlerException(StoredFileErasure.STORAGE_UNAVAILABLE, "the export location is not available");
        }
        try {
            return StagingFolder.staging(root, request.entity());
        } catch (StagingFolder.NotAFolderException refused) {
            throw new EraseHandlerException("LV_ERASE_PATH_REFUSED", "a link is in the place of a folder");
        }
    }
}
