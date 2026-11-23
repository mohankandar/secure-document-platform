package com.securedoc.benchmark.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securedoc.benchmark.score.Prediction;
import com.securedoc.benchmark.truth.Truth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Sends one page image to a local vision model with the versioned prompt and keeps only item types and
 * boxes from the answer. The model's raw answer is never written anywhere: it is parsed in memory and
 * dropped.
 */
public final class VisionClient {

    public static final String PROMPT_VERSION = "vision-prompt-v1";
    /** Pages are sent with their longer side at this many pixels (about 127 dpi for a Letter page). */
    public static final int MAX_SIDE = 1400;

    /** How a model writes boxes. Each model gets its own trained convention; the rest of the prompt is the same. */
    public enum BoxConvention {
        PIXELS("Give each box as [x1, y1, x2, y2] in pixels of this image, which is {W} pixels wide and {H} pixels high, with the origin at the top left."),
        PER_MILLE("Give each box as [x1, y1, x2, y2] on a 0 to 1000 scale of the image width and height, with the origin at the top left.");

        final String sentence;

        BoxConvention(String sentence) {
            this.sentence = sentence;
        }

        public static BoxConvention forModel(String model) {
            return model.toLowerCase(Locale.ROOT).startsWith("qwen3-vl") ? PER_MILLE : PIXELS;
        }
    }

    public record Answer(List<Prediction> items, double seconds, boolean parseFailed, int promptTokens, int outputTokens) {
    }

    private static final String TEMPLATE = loadTemplate();

    private final Ollama ollama;

    public VisionClient(Ollama ollama) {
        this.ollama = ollama;
    }

    public static String promptTemplate() {
        return TEMPLATE;
    }

    /** sha256 of the prompt template file, recorded with every result. */
    public static String promptSha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String prompt(BoxConvention convention, int width, int height) {
        String coords = convention.sentence.replace("{W}", Integer.toString(width)).replace("{H}", Integer.toString(height));
        return TEMPLATE.replace("{COORDINATES}", coords);
    }

    public Answer ask(String model, boolean thinks, BufferedImage page, int pageNumber) throws IOException, InterruptedException {
        BufferedImage image = fit(page);
        BoxConvention convention = BoxConvention.forModel(model);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        ObjectNode body = Ollama.JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", "30m");
        if (thinks) body.put("think", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt(convention, image.getWidth(), image.getHeight()));
        message.putArray("images").add(Base64.getEncoder().encodeToString(png.toByteArray()));
        // No JSON-schema "format": in a trial it made qwen2.5vl answer {"items": []} on pages with items.
        // The answer is parsed leniently instead (the JSON object inside the reply).
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("num_ctx", 8192);
        options.put("num_predict", 1024);

        long t = System.nanoTime();
        JsonNode response = ollama.post("/api/chat", body, Duration.ofMinutes(5));
        double seconds = (System.nanoTime() - t) / 1e9;
        String content = response.path("message").path("content").asText("");
        Parsed parsed = parse(content, convention, image.getWidth(), image.getHeight(), pageNumber, model);
        return new Answer(parsed.items(), seconds, parsed.failed(), response.path("prompt_eval_count").asInt(),
                response.path("eval_count").asInt());
    }

    static BufferedImage fit(BufferedImage page) {
        int longest = Math.max(page.getWidth(), page.getHeight());
        if (longest <= MAX_SIDE) return page;
        double s = (double) MAX_SIDE / longest;
        int w = (int) Math.round(page.getWidth() * s), h = (int) Math.round(page.getHeight() * s);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(page, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    record Parsed(List<Prediction> items, boolean failed) {
    }

    /** Keeps type and box only; anything else the model wrote is ignored. */
    static Parsed parse(String content, BoxConvention convention, int width, int height, int page, String model) {
        List<Prediction> items = new ArrayList<>();
        JsonNode root;
        try {
            String json = content.strip();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end < start) return new Parsed(items, true);
            root = Ollama.JSON.readTree(json.substring(start, end + 1));
        } catch (IOException | RuntimeException e) {
            return new Parsed(items, true);
        }
        JsonNode list = root.path("items");
        if (!list.isArray()) return new Parsed(items, true);
        for (JsonNode node : list) {
            String type = node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            JsonNode box = node.path("box");
            if (!Truth.ITEM_TYPES.contains(type) || !box.isArray() || box.size() != 4) continue;
            double x1 = box.get(0).asDouble(), y1 = box.get(1).asDouble(), x2 = box.get(2).asDouble(), y2 = box.get(3).asDouble();
            double sx = convention == BoxConvention.PER_MILLE ? 1000 : width;
            double sy = convention == BoxConvention.PER_MILLE ? 1000 : height;
            double l = clamp(Math.min(x1, x2) / sx), r = clamp(Math.max(x1, x2) / sx);
            double t = clamp(Math.min(y1, y2) / sy), b = clamp(Math.max(y1, y2) / sy);
            if (r - l <= 0 || b - t <= 0) continue;
            items.add(new Prediction(page, type, new double[]{l, t, r - l, b - t}, "vision:" + model));
        }
        return new Parsed(items, false);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String loadTemplate() {
        try (InputStream in = VisionClient.class.getResourceAsStream("/vision-prompt-v1.txt")) {
            if (in == null) throw new IllegalStateException("vision-prompt-v1.txt missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
