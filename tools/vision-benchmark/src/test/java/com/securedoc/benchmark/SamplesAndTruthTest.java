package com.securedoc.benchmark;

import com.securedoc.benchmark.samples.SampleGenerator;
import com.securedoc.benchmark.truth.Truth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplesAndTruthTest {

    @TempDir
    Path temp;

    @Test
    void theGeneratorMakesAReproducibleSetWithAnAnswerFilePerDocument() throws IOException {
        Path a = temp.resolve("a"), b = temp.resolve("b");
        Map<String, Truth> first = new SampleGenerator(33).generate(a);
        new SampleGenerator(33).generate(b);
        assertTrue(first.size() >= 20 && first.size() <= 30, "20 to 30 documents");
        for (String id : first.keySet()) {
            assertTrue(Files.exists(a.resolve(id + ".pdf")));
            assertEquals(Files.readString(a.resolve(id + ".truth.json")), Files.readString(b.resolve(id + ".truth.json")),
                    "same seed, same answer files");
        }
        // every page kind is covered, and a 60-page package is included
        var kinds = first.values().stream().flatMap(t -> t.pages().stream()).map(Truth.PageTruth::kind).distinct().toList();
        assertTrue(kinds.containsAll(Truth.PAGE_KINDS));
        assertTrue(first.values().stream().anyMatch(t -> t.pages().size() == 60));
        // answer files hold types and boxes only
        String json = Files.readString(a.resolve("public-01.truth.json"));
        assertFalse(json.matches("(?s).*\\d{3}-\\d{2}-\\d{4}.*"), "no SSN-shaped value in an answer file");
        assertTrue(Files.readString(a.resolve("SOURCES.md")).contains("public-26"));
    }

    @Test
    void answerFilesAreCheckedWhenRead() throws IOException {
        Path bad = temp.resolve("bad.truth.json");
        Files.writeString(bad, "{\"version\":1,\"pages\":[{\"page\":1,\"kind\":\"POSTCARD\",\"items\":[]}]}");
        assertThrows(Exception.class, () -> Truth.read(bad));
        Files.writeString(bad, "{\"version\":1,\"pages\":[{\"page\":1,\"kind\":\"TYPED\",\"items\":[{\"type\":\"SSN\",\"box\":[0,0,1]}]}]}");
        assertThrows(Exception.class, () -> Truth.read(bad));
        Truth ok = new Truth(1, List.of(new Truth.PageTruth(1, "CHECK", "VISION",
                List.of(new Truth.Item("ROUTING_NUMBER", new double[]{0.1, 0.2, 0.3, 0.04}, null)))));
        ok.write(bad);
        assertEquals("ROUTING_NUMBER", Truth.read(bad).page(1).items().get(0).type());
    }

    @Test
    void customerSamplesAreNumberedAndAnEmptyCustomerFolderStopsPartB() throws IOException {
        Path root = temp.resolve("samples");
        Files.createDirectories(root.resolve("customer"));
        IOException empty = assertThrows(IOException.class, () -> SampleSet.load(root, "customer"));
        assertTrue(empty.getMessage().contains("part A only"));

        Truth t = new Truth(1, List.of(new Truth.PageTruth(1, "TYPED", null, List.of())));
        for (String name : List.of("Zeta loan file", "alpha statement")) {
            Files.write(root.resolve("customer").resolve(name + ".pdf"), new byte[]{1});
            t.write(root.resolve("customer").resolve(name + ".truth.json"));
        }
        SampleSet set = SampleSet.load(root, "customer");
        assertEquals(List.of("customer-01", "customer-02"), set.samples().stream().map(SampleSet.Sample::id).toList());

        Files.write(root.resolve("customer").resolve("Quixotic mortgage.pdf"), new byte[]{1});
        IOException missing = assertThrows(IOException.class, () -> SampleSet.load(root, "customer"));
        assertFalse(missing.getMessage().contains("Quixotic"), "a customer file name never appears in a message");
    }

    @Test
    void aSamplesFolderInsideTheRepositoryIsRefused() {
        Path insideRepo = Path.of("").toAbsolutePath().resolve("target").resolve("samples");
        assertThrows(IOException.class, () -> SampleSet.load(insideRepo, "public"));
    }
}
