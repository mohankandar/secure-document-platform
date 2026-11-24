package com.securedoc.platform.infrastructure.intake;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PdfDecryptionPort.DecryptedPdf;
import com.securedoc.platform.domain.error.LvErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0063: RC4 and AES files made here; user and owner password; wrong password; nothing left behind. */
class PdfBoxDecryptionAdapterTest {

    private static final String USER = "Quartz-Lantern-58";
    private static final String OWNER = "Owner-Meadow-91";

    private final PdfBoxDecryptionAdapter adapter = new PdfBoxDecryptionAdapter();

    @Test
    void everyCipherOpensWithTheUserAndWithTheOwnerPassword() throws Exception {
        for (Cipher cipher : Cipher.values()) {
            for (String password : new String[]{USER, OWNER}) {
                try (DecryptedPdf out = adapter.decrypt(new ByteArrayInputStream(pdf(cipher, USER)), password.toCharArray())
                        .orElseThrow()) {
                    byte[] bytes = out.openStream().readAllBytes();
                    assertEquals(out.contentLength(), bytes.length);
                    try (PDDocument document = Loader.loadPDF(bytes)) {
                        assertFalse(document.isEncrypted(), cipher + ": no protection left");
                        assertEquals(2, document.getNumberOfPages());
                    }
                    assertEquals(2, new PdfBoxIntakeValidationAdapter().inspect(out.openStream()).pageCount());
                }
            }
        }
    }

    @Test
    void aWrongPasswordIsRefusedWithItsCodeNoHintAndNothingLeft() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        root.setLevel(Level.ALL);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        Set<Path> files = temporaryFiles();
        try {
            for (Cipher cipher : Cipher.values()) {
                for (String wrong : new String[]{"Wrong-Pebble-77", "", USER.toLowerCase()}) {
                    ValidationException refused = assertThrows(ValidationException.class, () -> adapter.decrypt(
                            new ByteArrayInputStream(pdf(cipher, USER)), wrong.toCharArray()));
                    assertEquals(LvErrorCode.INT_036, refused.lvCode());
                    assertEquals(PdfBoxDecryptionAdapter.WRONG_PASSWORD, refused.getMessage());
                    assertNull(refused.getCause());
                }
            }
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }
        assertEquals(files, temporaryFiles(), "no temporary file left");
        for (ILoggingEvent event : logs.list) {
            for (String secret : new String[]{USER, OWNER, "Wrong-Pebble-77"}) {
                assertFalse(event.getFormattedMessage().contains(secret), "a log line holds a password");
            }
        }
    }

    @Test
    void aPdfWithoutProtectionNeedsNoPassword() throws Exception {
        assertTrue(adapter.decrypt(new ByteArrayInputStream(pdf(null, null)), "anything".toCharArray()).isEmpty());
    }

    @Test
    void aFileThatIsNoPdfIsNotReadable() {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> adapter.decrypt(new ByteArrayInputStream("no pdf".getBytes()), USER.toCharArray()));
        assertEquals(LvErrorCode.INT_008, refused.lvCode());
    }

    @Test
    void theDecryptedCopyIsDeletedWhenClosed() throws Exception {
        Set<Path> files = temporaryFiles();
        adapter.decrypt(new ByteArrayInputStream(pdf(Cipher.AES_256, USER)), USER.toCharArray()).orElseThrow().close();
        assertEquals(files, temporaryFiles());
    }

    private static Set<Path> temporaryFiles() throws Exception {
        try (var list = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return list.filter(p -> p.getFileName().toString().startsWith("lexavault-decrypt-")).collect(Collectors.toSet());
        }
    }

    private enum Cipher { RC4_40, RC4_128, AES_128, AES_256 }

    private static byte[] pdf(Cipher cipher, String userPassword) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            if (cipher != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(OWNER, userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(switch (cipher) {
                    case RC4_40 -> 40;
                    case RC4_128, AES_128 -> 128;
                    case AES_256 -> 256;
                });
                policy.setPreferAES(cipher == Cipher.AES_128 || cipher == Cipher.AES_256);
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
