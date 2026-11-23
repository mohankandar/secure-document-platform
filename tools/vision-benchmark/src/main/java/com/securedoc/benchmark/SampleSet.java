package com.securedoc.benchmark;

import com.securedoc.benchmark.truth.Truth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The samples of one set ({@code public} or {@code customer}) under the samples root. Each sample is a PDF
 * with an answer file {@code <name>.truth.json} beside it. Customer samples are referred to as
 * {@code customer-01}, {@code customer-02}, ... in sorted file-name order; their file names never leave
 * this class.
 */
public record SampleSet(String name, List<Sample> samples) {

    public record Sample(String id, Path pdf, Truth truth) {
    }

    public static SampleSet load(Path root, String set) throws IOException {
        refuseInsideRepository(root);
        Path folder = root.resolve(set);
        if (!Files.isDirectory(folder)) {
            throw new IOException("No '" + set + "' folder under the samples root.");
        }
        List<Path> pdfs;
        try (Stream<Path> files = Files.list(folder)) {
            pdfs = files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        }
        if (pdfs.isEmpty()) {
            throw new IOException("The '" + set + "' folder has no PDF samples."
                    + ("customer".equals(set) ? " Part B waits for the owner's approved customer samples; run part A only." : ""));
        }
        List<Sample> samples = new ArrayList<>();
        int n = 0;
        for (Path pdf : pdfs) {
            n++;
            String base = pdf.getFileName().toString().replaceAll("(?i)\\.pdf$", "");
            String id = "customer".equals(set) ? String.format("customer-%02d", n) : base;
            Path truthFile = pdf.resolveSibling(base + ".truth.json");
            if (!Files.isRegularFile(truthFile)) {
                throw new IOException("Sample " + id + " has no answer file (<name>.truth.json beside the PDF).");
            }
            Truth truth;
            try {
                truth = Truth.read(truthFile);
            } catch (IOException | RuntimeException e) {
                throw new IOException("Answer file of " + id + " is not valid: " + e.getClass().getSimpleName(), e);
            }
            samples.add(new Sample(id, pdf, truth));
        }
        return new SampleSet(set, samples);
    }

    /** Samples and results must stay outside the repository. */
    static void refuseInsideRepository(Path root) throws IOException {
        for (Path p = root.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                throw new IOException("The samples root is inside a git repository. Samples and results must stay outside it.");
            }
        }
    }
}
