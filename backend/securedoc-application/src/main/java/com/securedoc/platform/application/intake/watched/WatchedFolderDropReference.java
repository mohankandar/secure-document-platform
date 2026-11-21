package com.securedoc.platform.application.intake.watched;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Task 0023 B3: how a watched-folder drop is named on screen, in audit records and in logs. Drop folders are often
 * named after a person or a case, so the name itself never leaves the filesystem adapter. The reference is the folder
 * key plus the first 12 hex characters of the SHA-256 of the drop folder's name, for example
 * {@code installed/3fa9c0e1b2d4}. IT can match it on the server by hashing a drop folder's name (UTF-8 bytes)
 * with SHA-256 and comparing the first 12 lower-case hex characters.
 */
public final class WatchedFolderDropReference {

    /** Hex characters of the hash that are kept. */
    public static final int DROP_ID_LENGTH = 12;

    private WatchedFolderDropReference() {}

    /** The reference of a drop in the folder installed with LexaVault. */
    public static String of(String dropFolderName) {
        return of(WatchedFolderSettingsService.INSTALLED_FOLDER, dropFolderName);
    }

    public static String of(String folderKey, String dropFolderName) {
        Objects.requireNonNull(folderKey, "folderKey");
        Objects.requireNonNull(dropFolderName, "dropFolderName");
        return folderKey + "/" + dropId(dropFolderName);
    }

    /** The short drop id alone (no folder key). */
    public static String dropId(String dropFolderName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(dropFolderName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, DROP_ID_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
