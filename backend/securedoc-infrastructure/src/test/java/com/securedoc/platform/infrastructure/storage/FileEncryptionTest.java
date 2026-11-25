package com.securedoc.platform.infrastructure.storage;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0060: stored files are encrypted as they are written and opened only when they are exactly what was written. */
@Timeout(60)
class FileEncryptionTest {
    private static final String MARKER = "SYNTHETIC-PLAINTEXT-MARKER-0060";
    private static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");

    @TempDir Path folder;
    private InMemoryKeyService keys;
    private FileEncryption files;

    @BeforeEach
    void keys() {
        keys = new InMemoryKeyService();
        keys.createKeys(CUSTOMER);
        files = FileEncryption.with(new StreamCipher(keys), CUSTOMER);
    }

    private EnvelopeInfo write(Path file, byte[] content) throws IOException {
        return files.write(new ByteArrayInputStream(content), file);
    }

    private byte[] read(EnvelopeInfo envelope, Path file) throws IOException {
        try (InputStream in = files.read(envelope, file)) {
            return in.readAllBytes();
        }
    }

    private static TechnicalFailureCode code(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(TechnicalDependencyException.class, call).failureCode();
    }

    @Test
    void everySizeComesBackAsItWasAndNothingPlainIsOnDisk() throws Exception {
        for (int size : new int[]{0, 1, 65_535, 65_536, 65_537, 131_072, 3_000_001}) {
            byte[] content = new byte[size];
            new Random(size).nextBytes(content);
            byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);
            if (size > 70_000) System.arraycopy(marker, 0, content, 65_530, marker.length); // across a chunk border
            Path file = folder.resolve("f" + size);
            EnvelopeInfo envelope = write(file, content);
            assertArrayEquals(content, read(envelope, file), "size " + size);
            assertEquals(Files.size(file), envelope.encryptedBytes());
            assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(MARKER));
            assertFalse(size > 0 && Arrays.equals(content, Files.readAllBytes(file)));
        }
    }

    @Test
    void smallReadsSkippingAndAnEarlyCloseWork() throws Exception {
        byte[] content = new byte[400_000];
        new Random(7).nextBytes(content);
        Path file = folder.resolve("f");
        EnvelopeInfo envelope = write(file, content);
        try (InputStream in = files.read(envelope, file)) {
            assertEquals(content[0] & 0xff, in.read());
            assertEquals(100_000, in.skip(100_000));
            byte[] some = in.readNBytes(1000);
            assertArrayEquals(Arrays.copyOfRange(content, 100_001, 101_001), some);
        }
        InputStream closed = files.read(envelope, file);
        closed.close();
        closed.close();
        assertThrows(IOException.class, closed::read);
        Files.delete(file); // no reader holds it: a Windows file in use could not be deleted
        // A closed reader lets go of the file; this would fail on Windows if it did not.
        assertFalse(Files.exists(file));
    }

    @Test
    void aChangedCutOrExchangedFileIsRefusedAsAWhole() throws Exception {
        byte[] content = (MARKER + " one").getBytes(StandardCharsets.UTF_8);
        Path one = folder.resolve("one");
        Path two = folder.resolve("two");
        EnvelopeInfo first = write(one, content);
        EnvelopeInfo second = write(two, (MARKER + " two").getBytes(StandardCharsets.UTF_8));
        byte[] stored = Files.readAllBytes(one);

        byte[] changed = stored.clone();
        changed[changed.length - 20] ^= 1;
        Files.write(one, changed);
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(first, one)));
        Files.write(one, Arrays.copyOf(stored, stored.length - 1));
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(first, one)));
        Files.write(one, content); // a plain file in place of the encrypted one
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(first, one)));
        Files.copy(two, one, java.nio.file.StandardCopyOption.REPLACE_EXISTING); // another file of the same customer
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(first, one)));
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(second.withWrappedKey(first.wrappedKey(),
                first.keyVersion()), two)), "another file's key");

        Files.write(one, stored);
        assertArrayEquals(content, read(first, one));
    }

    @Test
    void anotherCustomersFileIsNeverOpened() throws Exception {
        CustomerId other = CustomerId.fromTenantId("TEN-TEST-0002");
        keys.createKeys(other);
        Path file = folder.resolve("f");
        EnvelopeInfo envelope = FileEncryption.with(new StreamCipher(keys), other)
                .write(new ByteArrayInputStream(new byte[]{1, 2, 3}), file);
        assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, code(() -> read(envelope, file)));
    }

    @Test
    void theKeyServiceDownOrRefusingIsARetryableFailureWithItsCodeAndLeavesNoFile() throws Exception {
        Path stored = folder.resolve("stored");
        EnvelopeInfo envelope = write(stored, new byte[]{1, 2, 3});
        Path fresh = folder.resolve("fresh");

        keys.down(true);
        for (var call : new org.junit.jupiter.api.function.Executable[]{() -> read(envelope, stored),
                () -> write(fresh, new byte[]{4})}) {
            TechnicalDependencyException down = assertThrows(TechnicalDependencyException.class, call);
            assertEquals(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, down.failureCode());
            assertEquals(TechnicalDependency.KEY_SERVICE, down.dependency());
            assertTrue(down.retryable());
        }
        keys.down(false);
        keys.denied(true);
        assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, code(() -> read(envelope, stored)));
        assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, code(() -> write(fresh, new byte[]{4})));
        keys.denied(false);
        assertFalse(Files.exists(fresh), "a failed write leaves nothing");
        assertArrayEquals(new byte[]{1, 2, 3}, read(envelope, stored));

        keys.destroyCustomerKeys(CUSTOMER);
        assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, code(() -> read(envelope, stored)), "after a customer exit");
    }

    @Test
    void anOlderKeyVersionStillOpens() throws Exception {
        Path file = folder.resolve("f");
        EnvelopeInfo envelope = write(file, new byte[]{9});
        keys.rotate(CUSTOMER);
        assertArrayEquals(new byte[]{9}, read(envelope, file));
    }

    @Test
    void theDevelopmentModeWritesPlainAndNeverOpensAnEncryptedFile() throws Exception {
        Path encrypted = folder.resolve("encrypted");
        EnvelopeInfo envelope = write(encrypted, new byte[]{1});
        FileEncryption plain = FileEncryption.developmentPlain();
        assertNull(plain.write(new ByteArrayInputStream(new byte[]{5}), folder.resolve("plain")));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(folder.resolve("plain")));
        assertFalse(plain.encrypts());
        assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, code(() -> plain.read(envelope, encrypted)));
    }

    @Test
    void aSourceThatFailsLeavesNoFile() {
        Path file = folder.resolve("f");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic");
            }
        };
        assertThrows(IOException.class, () -> files.write(failing, file));
        assertFalse(Files.exists(file));
    }

    @Test
    void aFailureAfterTheFirstBytesIsTheReadersFailureNeverAnEarlyEnd() throws Exception {
        InputStream in = DecryptingInputStream.open(out -> {
            out.write(new byte[10]);
            throw new IOException("synthetic");
        });
        assertThrows(IOException.class, in::readAllBytes);
        assertThrows(IOException.class, in::read, "it stays failed");
    }
}
