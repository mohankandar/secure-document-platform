package com.securedoc.benchmark;

import com.securedoc.benchmark.routing.PageSignals;
import com.securedoc.benchmark.score.Prediction;
import com.securedoc.benchmark.vision.Ollama;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one run measured. Ids, numbers, types and boxes only: no value, no text, no file name. */
public final class Results {

    public String date;
    public String set;
    public String gpu;
    public String ollamaVersion;
    public String ollamaNumParallel;
    public String promptVersion;
    public String promptSha256;
    /** Box convention used per vision model (round 2 onwards; absent in round 1 results). */
    public Map<String, String> boxConventions = new LinkedHashMap<>();
    public double threshold;
    public List<Double> sweep = new ArrayList<>();
    public double visionThreshold;
    public ModelRun textModel;
    public List<ModelRun> visionModels = new ArrayList<>();
    public List<DocResult> documents = new ArrayList<>();

    public static final class ModelRun {
        public Ollama.ModelInfo info;
        public double loadSeconds;
        public double warmUpSeconds;
        public int gpuBaselineMiB;
        public int gpuPeakMiB;
        public Map<String, Long> vramBytes = new LinkedHashMap<>();
        public List<ConcurrencyLevel> concurrency = new ArrayList<>();
        public int errors;
    }

    public record ConcurrencyLevel(int requests, double meanLatencySeconds, double maxLatencySeconds,
                                   double wallSeconds, double pagesPerMinute) {
    }

    public static final class DocResult {
        public String id;
        public int pages;
        public List<String> kinds = new ArrayList<>();
        public List<String> expectedRoutes = new ArrayList<>();
        public List<PageSignals> routing = new ArrayList<>();
        public TextResult text;
        public Map<String, List<VisionPage>> vision = new LinkedHashMap<>();
    }

    public static final class TextResult {
        public double totalSeconds;
        public double extractionSeconds;
        public double modelSeconds;
        public int ocrPages;
        public String failure;
        public List<Prediction> findings = new ArrayList<>();
    }

    public record VisionPage(int page, double seconds, boolean parseFailed, String error,
                             int promptTokens, int outputTokens, List<Prediction> items) {
    }
}
