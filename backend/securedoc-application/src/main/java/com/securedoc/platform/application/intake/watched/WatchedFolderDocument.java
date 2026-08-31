package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.intake.command.UploadContent;
import java.util.Objects;

public record WatchedFolderDocument(String fileName, String mimeType, UploadContent content) {
    public WatchedFolderDocument {
        Objects.requireNonNull(fileName); Objects.requireNonNull(mimeType); Objects.requireNonNull(content);
    }
}
