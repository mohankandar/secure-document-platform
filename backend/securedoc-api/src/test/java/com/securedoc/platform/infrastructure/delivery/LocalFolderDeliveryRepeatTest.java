package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DeliveryPort.BatchDeliveryReference;
import com.securedoc.platform.application.port.out.DeliveryPort.DeliveryBatch;
import com.securedoc.platform.application.port.out.DeliveryPort.DeliveryItem;
import com.securedoc.platform.application.port.out.DeliveryPort.DeliveryProgressListener;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0086: a server killed after it published a case run's folder and before it recorded the delivery. The next try
 * takes the folder as its own finished delivery only when it is exactly that; anything else stays LV-DLV-010.
 */
class LocalFolderDeliveryRepeatTest {
    @TempDir Path root;
    private LocalFolderDeliveryAdapter adapter;
    private final TenantId tenant = TenantId.generate();
    private final PackageId packageId = PackageId.generate();
    private final DocumentId one = DocumentId.generate();
    private final DocumentId two = DocumentId.generate();
    private final byte[] first = bytes(200_000, 1);
    private final byte[] second = bytes(70_000, 2);
    private final List<DeliveryPort.DeliveryReference> acknowledged = new ArrayList<>();

    @BeforeEach
    void adapter() {
        LocalDeliveryProperties properties = new LocalDeliveryProperties();
        properties.setOutputPath(root.toString());
        adapter = new LocalFolderDeliveryAdapter(properties);
    }

    // ---------------------------------------------------------------- accepted

    @Test
    void theOwnFinishedFolderIsTheDeliveryAndNothingIsWritten() throws Exception {
        BatchDeliveryReference delivered = adapter.deliverBatch(batch());
        assertFalse(delivered.alreadyReceived());
        Map<String, String> before = state();

        BatchDeliveryReference again = adapter.deliverBatch(batch());

        assertEquals(delivered.documents(), again.documents(), "the same references");
        assertEquals(delivered.destinationType(), again.destinationType());
        assertTrue(again.alreadyReceived(), "audited with LV-DLV-009");
        assertEquals(before, state(), "nothing written, changed or left");
    }

    /**
     * The export service saves every single acknowledgement at once and asks only for the others at the next try. This
     * delivery is all or nothing, so its documents are acknowledged by its result, in one save: a server killed between
     * two single ones would leave a folder the next try could not compare.
     */
    @Test
    void documentsAreAcknowledgedTogetherByTheResult() throws Exception {
        assertEquals(2, adapter.deliverBatch(batch()).documents().size());
        assertEquals(2, adapter.deliverBatch(batch()).documents().size());
        assertTrue(acknowledged.isEmpty());
    }

    // ---------------------------------------------------------------- refused

    @Test
    void aMissingFileOrFolderIsRefused() throws Exception {
        refusedAfter(() -> Files.delete(file(one, "a.pdf")));
        refusedAfter(() -> deleteTree(folder(one)));
        refusedAfter(() -> {
            deleteTree(cycle());
            Files.createDirectory(cycle());
        });
    }

    @Test
    void anExtraFileOrFolderIsRefused() throws Exception {
        refusedAfter(() -> Files.writeString(cycle().resolve("extra.txt"), "x"));
        refusedAfter(() -> Files.writeString(file(one, "b.pdf"), "x"));
        refusedAfter(() -> Files.createDirectory(cycle().resolve("extra")));
        refusedAfter(() -> Files.createDirectory(file(one, "sub")));
    }

    @Test
    void aDifferentFileIsRefused() throws Exception {
        for (int at : new int[] {0, first.length / 2, first.length - 1}) {
            byte[] other = first.clone();
            other[at] ^= 1;
            refusedAfter(() -> Files.write(file(one, "a.pdf"), other));
        }
        byte[] lastFile = second.clone();
        lastFile[lastFile.length - 1] ^= 1;
        refusedAfter(() -> Files.write(file(two, "b.pdf"), lastFile));
    }

    @Test
    void aShorterOrLongerFileIsRefused() throws Exception {
        refusedAfter(() -> Files.write(file(one, "a.pdf"), Arrays.copyOf(first, first.length - 1)));
        refusedAfter(() -> Files.write(file(one, "a.pdf"), Arrays.copyOf(first, first.length + 1)));
        refusedAfter(() -> Files.write(file(one, "a.pdf"), new byte[0]));
    }

    @Test
    void aNameInOtherCapitalsIsRefused() throws Exception {
        refusedAfter(() -> {
            Files.move(file(one, "a.pdf"), file(one, "temp"));
            Files.move(file(one, "temp"), file(one, "A.pdf"));
        });
    }

    @Test
    void aFolderInPlaceOfAFileAndAFileInPlaceOfAFolderAreRefused() throws Exception {
        refusedAfter(() -> {
            Files.delete(file(one, "a.pdf"));
            Files.createDirectory(file(one, "a.pdf"));
        });
        refusedAfter(() -> {
            deleteTree(folder(one));
            Files.writeString(folder(one), "x");
        });
        refusedAfter(() -> {
            deleteTree(cycle());
            Files.writeString(cycle(), "x");
        });
    }

    @Test
    void aLinkedFolderIsRefusedEvenWhenWhatItPointsToIsEqual() throws Exception {
        for (String what : List.of("document", "cycle", "dangling")) {
            Path target = Files.createDirectories(root.resolve("elsewhere")).resolve(what);
            refusedAfter(() -> {
                Path link = what.equals("document") ? folder(one) : cycle();
                if (what.equals("dangling")) {
                    deleteTree(link);
                    Files.createDirectory(target);
                } else {
                    Files.move(link, target);
                }
                linkToFolder(link, target);
                if (what.equals("dangling")) Files.delete(target);
            });
        }
    }

    @Test
    void aLinkedFileIsRefusedEvenWhenWhatItPointsToIsEqual() throws Exception {
        Path target = root.resolve("a-elsewhere.pdf");
        Files.write(target, first);
        Path probe = root.resolve("probe");
        boolean links = true;
        try {
            Files.createSymbolicLink(probe, target);
        } catch (IOException noRight) {
            links = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(links, "file links need a right this machine's user may not have");
        refusedAfter(() -> {
            Files.delete(file(one, "a.pdf"));
            Files.createSymbolicLink(file(one, "a.pdf"), target);
        });
    }

    // ---------------------------------------------------------------- failures

    @Test
    void aFailureWhileComparingIsRetriedAndNeverCountsAsEqual() throws Exception {
        adapter.deliverBatch(batch());
        Map<String, String> before = state();
        DeliveryBatch broken = batch(() -> new InputStream() {
            private int read;

            @Override
            public int read() throws IOException {
                if (read == first.length / 2) throw new IOException("storage");
                return first[read++] & 0xff;
            }
        }, () -> new ByteArrayInputStream(second));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class, () -> adapter.deliverBatch(broken));

        assertEquals(TechnicalFailureCode.DELIVERY_IO, failure.failureCode());
        assertTrue(failure.retryable());
        assertEquals(before, state());
        assertTrue(adapter.deliverBatch(batch()).alreadyReceived(), "the next try");
    }

    // ---------------------------------------------------------------- the race

    @Test
    void theLoserOfARaceTakesTheEqualFolderAndLeavesNoStaging() throws Exception {
        BatchDeliveryReference[] winner = new BatchDeliveryReference[1];
        BatchDeliveryReference loser = adapter.deliverBatch(batch(() -> new ByteArrayInputStream(first), () -> {
            if (winner[0] == null) winner[0] = adapter.deliverBatch(batch());
            return new ByteArrayInputStream(second);
        }));

        assertFalse(winner[0].alreadyReceived());
        assertTrue(loser.alreadyReceived());
        assertEquals(winner[0].documents(), loser.documents());
        assertEquals(0, staged());
    }

    @Test
    void theLoserOfARaceNeverReplacesWhatAppeared() throws Exception {
        for (String what : List.of("empty", "other", "file")) {
            deleteTree(root.resolve(tenant.value().toString()));
            DeliveryBatch racing = batch(() -> new ByteArrayInputStream(first), () -> {
                try {
                    Files.createDirectories(cycle().getParent());
                    if (what.equals("file")) Files.writeString(cycle(), "other");
                    else Files.createDirectories(cycle());
                    if (what.equals("other")) Files.writeString(cycle().resolve("other.txt"), "other");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return new ByteArrayInputStream(second);
            });

            TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                    () -> adapter.deliverBatch(racing), what);

            assertEquals(LvErrorCode.DLV_010, failure.lvCode(), what);
            assertFalse(failure.retryable(), what);
            assertEquals(0, staged(), what);
            if (what.equals("file")) {
                assertEquals("other", Files.readString(cycle()));
            } else {
                try (var left = Files.list(cycle())) {
                    assertEquals(what.equals("other") ? 1 : 0, left.count(), what);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Change {
        void make() throws Exception;
    }

    /** A finished delivery, then the change, then the next try. */
    private void refusedAfter(Change change) throws Exception {
        deleteTree(root.resolve(tenant.value().toString()));
        adapter.deliverBatch(batch());
        change.make();
        Map<String, String> before = state();
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch()));
        assertEquals(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, failure.failureCode());
        assertEquals(LvErrorCode.DLV_010, failure.lvCode());
        assertFalse(failure.retryable());
        assertEquals(before, state(), "the folder is not touched");
    }

    private DeliveryBatch batch() {
        return batch(() -> new ByteArrayInputStream(first), () -> new ByteArrayInputStream(second));
    }

    private DeliveryBatch batch(DeliveryPort.ContentSource a, DeliveryPort.ContentSource b) {
        return new DeliveryBatch(tenant, packageId, 1, "sub-1", null, Map.of(), Map.of(),
                List.of(new DeliveryItem(one, "doc-1", "a.pdf", a, first.length, "application/pdf", null, null),
                        new DeliveryItem(two, "doc-2", "b.pdf", b, second.length, "application/pdf", null, null)),
                new DeliveryProgressListener() {
                    @Override
                    public void documentAcknowledged(DeliveryPort.DeliveryReference reference) {
                        acknowledged.add(reference);
                    }
                });
    }

    private Path cycle() {
        return root.resolve(tenant.value().toString()).resolve(packageId.value().toString()).resolve("cycle-1");
    }

    private Path folder(DocumentId document) {
        return cycle().resolve(document.value().toString());
    }

    private Path file(DocumentId document, String name) {
        return folder(document).resolve(name);
    }

    private long staged() throws IOException {
        try (var staging = Files.list(root.resolve(tenant.value().toString()).resolve(".staging"))) {
            return staging.count();
        }
    }

    /** Every path, with a file's bytes (as a hash) and time; links are not followed. */
    private Map<String, String> state() throws IOException {
        Map<String, String> state = new TreeMap<>();
        collect(root, state);
        return state;
    }

    private void collect(Path folder, Map<String, String> state) throws IOException {
        try (var children = Files.list(folder)) {
            for (Path path : children.toList()) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                state.put(root.relativize(path).toString(), attributes.isRegularFile()
                        ? Arrays.hashCode(Files.readAllBytes(path)) + " " + attributes.lastModifiedTime() : "-");
                if (attributes.isDirectory() && !attributes.isOther() && !path.endsWith(".staging")) collect(path, state);
            }
        }
    }

    /** A symbolic link, or on Windows without that right a junction. */
    private static void linkToFolder(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException noRight) {
            assertEquals(0, new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(), "junction");
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory() && !attributes.isOther()) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) deleteTree(child);
            }
        }
        Files.delete(path);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
