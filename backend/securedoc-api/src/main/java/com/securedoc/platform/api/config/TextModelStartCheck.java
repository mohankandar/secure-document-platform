package com.securedoc.platform.api.config;

import com.securedoc.platform.ai.analysis.OllamaTextModelPin;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Task 0110 (hardening: text model pinned and local). With the Ollama provider, the start is refused when the text
 * model's server is not local (LV-AI-006), the model is not installed there, or its digest is not the approved one
 * (LV-AI-008). A server that does not answer at start is logged; the start goes on and the model is not used until it
 * answers (the AI health check reports it).
 */
@Component
public final class TextModelStartCheck {

    private static final Logger log = LoggerFactory.getLogger(TextModelStartCheck.class);

    private final AiRuntimeProperties properties;

    public TextModelStartCheck(AiRuntimeProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @PostConstruct
    void check() {
        if (!"ollama".equalsIgnoreCase(String.valueOf(properties.getProvider()).trim())) return;
        String model = properties.getModel() == null ? "" : properties.getModel().trim();
        if (model.isEmpty()) throw new IllegalStateException("The text model must be named (securedoc.ai.model)");
        String digest;
        try {
            digest = OllamaTextModelPin.normalized(properties.getModelDigest());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(LvErrorCode.AI_008.code() + ": the text model " + model
                    + " has no approved digest. Set securedoc.ai.model-digest (SECUREDOC_AI_MODEL_DIGEST) to its"
                    + " 64-character digest; a change of model means the model, its digest and a new benchmark.");
        }
        for (String baseUrl : servers()) {
            OllamaTextModelPin.Check check = OllamaTextModelPin.check(baseUrl, model, digest);
            switch (check.result()) {
                case APPROVED -> log.info("TEXT_MODEL_APPROVED model={} digest={}", model, digest);
                case NOT_REACHABLE -> log.warn("TEXT_MODEL_NOT_CHECKED code={} reasonCode=NOT_REACHABLE model={}",
                        LvErrorCode.AI_001.code(), model);
                case NOT_LOCAL -> throw new IllegalStateException(LvErrorCode.AI_006.code()
                        + ": the text model's server is not on this machine or a private network: " + baseUrl);
                case NOT_INSTALLED -> throw new IllegalStateException(LvErrorCode.AI_008.code()
                        + ": the text model " + model + " is not installed on " + baseUrl
                        + ". Pull it (ollama pull " + model + ") and start again.");
                case DIFFERENT -> throw new IllegalStateException(LvErrorCode.AI_008.code()
                        + ": the text model " + model + " on " + baseUrl + " is not the approved one (approved digest "
                        + digest + ", installed " + check.installedDigest() + "). Pull the approved model, or set the"
                        + " new digest in securedoc.ai.model-digest after its benchmark.");
            }
        }
    }

    private List<String> servers() {
        if (!properties.isPoolEnabled()) return List.of(properties.getBaseUrl());
        return OllamaInferencePoolConfiguration.parse(properties.getPoolNodeUrls()).stream()
                .map(OllamaInferencePoolConfiguration.NodeTarget::baseUrl).toList();
    }
}
