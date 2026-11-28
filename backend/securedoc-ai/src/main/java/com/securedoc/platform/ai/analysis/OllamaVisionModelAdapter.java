package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.PageImagePort.PageImage;
import com.securedoc.platform.application.port.out.VisionModelPort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0064: the local vision model on Ollama, asked exactly as in the benchmark (reports 0033 and 0040): one page
 * image, prompt v2, temperature 0, boxes on a 0 to 1000 scale.
 *
 * <ul>
 *   <li><b>Local only.</b> A server whose address is not on this machine or a private network is never asked.</li>
 *   <li><b>Pinned by digest.</b> The server's digest of the model must be the approved one, before and after each page;
 *       otherwise the model is not used (LV-AI-008).</li>
 *   <li><b>No value is kept.</b> The answer is parsed in memory to kinds and boxes and dropped; it is never logged.</li>
 *   <li><b>Fail closed.</b> Anything unclear is a page that was not read (it goes to a person).</li>
 * </ul>
 */
public final class OllamaVisionModelAdapter implements VisionModelPort {
    private static final Logger log = LoggerFactory.getLogger(OllamaVisionModelAdapter.class);

    public static final String PROMPT_VERSION = "vision-prompt-v2";
    /** Task 0083: the second check's own prompt, asked of the same model. */
    public static final String SECOND_CHECK_VERSION = "second-check-vision-v1";
    private static final String COORDINATES = "Give each box as [x1, y1, x2, y2] on a 0 to 1000 scale of the image "
            + "width and height, with the origin at the top left.";
    private static final double SCALE = 1000;
    private static final int CONTEXT_TOKENS = 8192;
    private static final int ANSWER_TOKENS = 2048;
    private static final int ATTEMPTS = 2;

    private final String promptVersion;
    private final String prompt;
    private final String model;
    private final String digest;
    private final Duration timeout;
    private final String keepAlive;
    private final String host;
    private final URI chat;
    private final HttpClient http;
    private final ObjectMapper json;
    private final OllamaModelDigests digests;

    public OllamaVisionModelAdapter(String baseUrl, String model, String digest, Duration timeout, String keepAlive) {
        this(baseUrl, model, digest, timeout, keepAlive, PROMPT_VERSION);
    }

    public OllamaVisionModelAdapter(String baseUrl, String model, String digest, Duration timeout, String keepAlive,
                                    String promptVersion) {
        this.promptVersion = promptVersion;
        this.prompt = prompt(promptVersion);
        String base = Objects.requireNonNull(baseUrl, "baseUrl").trim().replaceAll("/+$", "");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("The vision model must be named");
        String pinned = digest == null ? "" : digest.trim().toLowerCase(Locale.ROOT).replaceFirst("^sha256:", "");
        if (!pinned.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The vision model's digest must be its 64 hexadecimal characters");
        }
        this.model = model.trim();
        this.digest = pinned;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.keepAlive = keepAlive == null || keepAlive.isBlank() ? "5m" : keepAlive.trim();
        this.chat = URI.create(base + "/api/chat");
        this.host = chat.getHost();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.json = JsonMapper.builderWithJackson2Defaults().build();
        this.digests = new OllamaModelDigests(base, http, json);
    }

    @Override
    public VisionPageResult read(PageImage image, int page) {
        try {
            if (!local()) return notRead(page, LvErrorCode.AI_006, "SERVER_NOT_LOCAL");
            VisionPageResult refused = refuseUnlessApproved(page);
            if (refused != null) return refused;
            VisionPageResult result = ask(image, page);
            // The model must be the approved one when the answer is used, not only when it was asked.
            refused = result.read() ? refuseUnlessApproved(page) : null;
            return refused == null ? result : refused;
        } catch (HttpTimeoutException e) {
            return notRead(page, LvErrorCode.AI_002, "TIMEOUT");
        } catch (IOException e) {
            return notRead(page, LvErrorCode.AI_001, "NOT_AVAILABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vision page reading was interrupted");
        }
    }

    private VisionPageResult refuseUnlessApproved(int page) throws IOException, InterruptedException {
        Optional<String> installed = digests.of(model);
        if (installed.isEmpty()) return notRead(page, LvErrorCode.AI_004, "MODEL_NOT_INSTALLED");
        if (!installed.get().equals(digest)) {
            log.error("VISION_MODEL_REFUSED code={} reasonCode=MODEL_DIGEST_MISMATCH model={} approved={} installed={}",
                    LvErrorCode.AI_008.code(), model, digest, AiModel.of(model, promptVersion, installed.get()).digest());
            return notRead(page, LvErrorCode.AI_008, "MODEL_DIGEST_MISMATCH");
        }
        return null;
    }

    private static VisionPageResult notRead(int page, LvErrorCode code, String reason) {
        return VisionPageResult.notRead(page, code.code(), reason);
    }

    private VisionPageResult ask(PageImage image, int page) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", keepAlive);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt,
                "images", List.of(Base64.getEncoder().encodeToString(image.png())))));
        body.put("options", Map.of("temperature", 0, "num_ctx", CONTEXT_TOKENS, "num_predict", ANSWER_TOKENS));
        HttpRequest request = HttpRequest.newBuilder(chat).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = AiServerRequestLimit.send(chat,
                        () -> http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            } catch (HttpTimeoutException timedOut) {
                throw timedOut;
            } catch (IOException failed) {
                if (attempt < ATTEMPTS) continue;
                throw failed;
            }
            int status = response.statusCode();
            if (status == 200) return answer(response.body(), page);
            boolean passing = status == 429 || status >= 500;
            if (passing && attempt < ATTEMPTS) continue;
            return notRead(page, status == 429 ? LvErrorCode.AI_003 : status >= 500 ? LvErrorCode.AI_001
                    : LvErrorCode.AI_004, "HTTP_" + status);
        }
    }

    private VisionPageResult answer(byte[] body, int page) {
        try {
            JsonNode envelope = json.readTree(body);
            if (!envelope.path("done").asBoolean(false) || "length".equals(envelope.path("done_reason").asString(""))) {
                return notRead(page, LvErrorCode.AI_005, "ANSWER_CUT_SHORT");
            }
            return parse(envelope.path("message").path("content").asString(""), page);
        } catch (JacksonException | IllegalArgumentException notAnAnswer) {
            return notRead(page, LvErrorCode.AI_005, "ANSWER_NOT_READABLE");
        }
    }

    /** Kinds and boxes only; anything else the model wrote is dropped. An item that cannot be placed is not lost. */
    VisionPageResult parse(String content, int page) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        JsonNode items = null;
        if (start >= 0 && end > start) {
            try {
                items = json.readTree(content.substring(start, end + 1)).path("items");
            } catch (JacksonException notJson) {
                items = null;
            }
        }
        if (items == null || !items.isArray()) return notRead(page, LvErrorCode.AI_005, "ANSWER_NOT_READABLE");
        List<VisionPageResult.Item> found = new ArrayList<>();
        boolean incomplete = false;
        for (JsonNode node : items) {
            VisionPageResult.Item item = item(node);
            if (item == null) incomplete = true;
            else if (!found.contains(item)) found.add(item);
        }
        return VisionPageResult.read(page, AiModel.of(model, promptVersion, digest), found, incomplete);
    }

    private static VisionPageResult.Item item(JsonNode node) {
        VisionPageResult.Kind kind;
        try {
            kind = VisionPageResult.Kind.valueOf(node.path("type").asString("").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownKind) {
            return null;
        }
        JsonNode box = node.path("box");
        if (!box.isArray() || box.size() != 4) return null;
        double[] n = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!box.get(i).isNumber()) return null;
            n[i] = Math.max(0, Math.min(1, box.get(i).doubleValue() / SCALE));
        }
        double left = Math.min(n[0], n[2]);
        double top = Math.min(n[1], n[3]);
        double width = Math.max(n[0], n[2]) - left;
        double height = Math.max(n[1], n[3]) - top;
        while (left + width > 1.0) width = Math.nextDown(width);
        while (top + height > 1.0) height = Math.nextDown(height);
        return width <= 0 || height <= 0 ? null : new VisionPageResult.Item(kind,
                BoundingBox.of(left, top, width, height));
    }

    /** This machine or a private network: never an address on the internet. Asked each time (an address can change). */
    private boolean local() throws IOException {
        return LocalModelServer.isLocal(host);
    }

    static String prompt() {
        return prompt(PROMPT_VERSION);
    }

    static String prompt(String version) {
        try (InputStream in = OllamaVisionModelAdapter.class.getResourceAsStream("/" + version + ".txt")) {
            if (in == null) throw new IllegalStateException(version + " is missing");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")
                    .replace("{COORDINATES}", COORDINATES);
            return SECOND_CHECK_VERSION.equals(version) ? text.trim() : text;
        } catch (IOException e) {
            throw new IllegalStateException(version + " could not be read");
        }
    }
}
