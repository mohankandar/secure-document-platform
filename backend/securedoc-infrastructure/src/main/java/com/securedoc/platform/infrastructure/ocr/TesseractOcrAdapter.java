package com.securedoc.platform.infrastructure.ocr;

import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Local/on-prem OCR adapter using the Tesseract CLI and TSV output.
 *
 * <p>The adapter never logs recognized text. Tesseract word boxes are normalized
 * into the provider-neutral OCR span model consumed by the hybrid PDF extractor.
 */
@Component
public class TesseractOcrAdapter implements OcrPort {

    private final TesseractOcrProperties properties;

    public TesseractOcrAdapter(TesseractOcrProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public OcrPageOutput recognize(OcrPageInput input) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("securedoc-ocr-");
            Path imagePath = tempDir.resolve("page.png");
            Path outputBase = tempDir.resolve("result");
            Files.write(imagePath, input.imageBytes());

            List<String> command = List.of(
                    properties.getExecutable(),
                    imagePath.toString(),
                    outputBase.toString(),
                    "-l", properties.getLanguage(),
                    "--psm", Integer.toString(properties.getPageSegmentationMode()),
                    "tsv"
            );

            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            boolean completed = process.waitFor(
                    Math.max(1, properties.getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new OcrProcessingException(TechnicalFailureCode.OCR_TIMEOUT, true, "OCR provider timed out");
            }
            if (process.exitValue() != 0) {
                throw new OcrProcessingException(TechnicalFailureCode.OCR_PROCESS_FAILED, false, "OCR provider returned a non-zero exit status");
            }

            Path tsv = Path.of(outputBase + ".tsv");
            if (!Files.isRegularFile(tsv)) {
                throw new OcrProcessingException(TechnicalFailureCode.OCR_PROCESS_FAILED, false, "OCR provider did not produce expected output");
            }

            return parseTsv(Files.readAllLines(tsv, StandardCharsets.UTF_8),
                    input.pixelWidth(), input.pixelHeight());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrProcessingException(TechnicalFailureCode.OCR_PROCESS_FAILED, false, "OCR processing interrupted", e);
        } catch (IOException e) {
            throw new OcrProcessingException(TechnicalFailureCode.OCR_UNAVAILABLE, false, "OCR provider could not be executed", e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private OcrPageOutput parseTsv(List<String> lines, int pixelWidth, int pixelHeight) {
        List<WordRow> words = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\\t", 12);
            if (cols.length < 12 || !"5".equals(cols[0])) {
                continue;
            }

            String text = cols[11] == null ? "" : cols[11].strip();
            if (text.isBlank()) {
                continue;
            }

            int block = parseInt(cols[2]);
            int paragraph = parseInt(cols[3]);
            int lineNumber = parseInt(cols[4]);
            int wordNumber = parseInt(cols[5]);
            int left = parseInt(cols[6]);
            int top = parseInt(cols[7]);
            int width = parseInt(cols[8]);
            int height = parseInt(cols[9]);
            double confidence = normalizeConfidence(cols[10]);

            if (width <= 0 || height <= 0) {
                continue;
            }
            words.add(new WordRow(block, paragraph, lineNumber, wordNumber,
                    left, top, width, height, confidence, text));
        }

        words.sort(Comparator
                .comparingInt(WordRow::block)
                .thenComparingInt(WordRow::paragraph)
                .thenComparingInt(WordRow::line)
                .thenComparingInt(WordRow::word));

        StringBuilder text = new StringBuilder();
        List<OcrTextSpan> spans = new ArrayList<>();
        int previousBlock = -1;
        int previousParagraph = -1;
        int previousLine = -1;
        double confidenceSum = 0.0;
        int confidenceCount = 0;

        for (WordRow word : words) {
            boolean newLine = previousLine >= 0
                    && (word.block() != previousBlock
                    || word.paragraph() != previousParagraph
                    || word.line() != previousLine);

            if (!text.isEmpty()) {
                text.append(newLine ? '\n' : ' ');
            }
            int start = text.length();
            text.append(word.text());
            int end = text.length();

            double left = clamp((double) word.left() / pixelWidth);
            double top = clamp((double) word.top() / pixelHeight);
            double width = clamp((double) word.width() / pixelWidth);
            double height = clamp((double) word.height() / pixelHeight);
            if (left + width > 1.0) width = Math.max(0.0, 1.0 - left);
            if (top + height > 1.0) height = Math.max(0.0, 1.0 - top);

            if (width > 0.0 && height > 0.0) {
                spans.add(new OcrTextSpan(start, end, left, top, width, height, word.confidence()));
            }

            if (word.confidence() > 0.0) {
                confidenceSum += word.confidence();
                confidenceCount++;
            }
            previousBlock = word.block();
            previousParagraph = word.paragraph();
            previousLine = word.line();
        }

        double confidence = confidenceCount == 0 ? 0.0 : confidenceSum / confidenceCount;
        return new OcrPageOutput(text.toString(), spans, clamp(confidence));
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double normalizeConfidence(String value) {
        try {
            double raw = Double.parseDouble(value);
            if (raw < 0.0) {
                return 0.0;
            }
            return clamp(raw / 100.0);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup. Do not hide OCR result/failure.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private record WordRow(int block, int paragraph, int line, int word,
                           int left, int top, int width, int height,
                           double confidence, String text) {
    }
}
