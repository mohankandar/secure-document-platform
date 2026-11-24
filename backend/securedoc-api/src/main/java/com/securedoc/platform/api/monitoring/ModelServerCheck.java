package com.securedoc.platform.api.monitoring;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;
import com.securedoc.platform.api.error.LvPlatformCode;
import com.securedoc.platform.domain.error.LvErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Task 0057: the AI model server. Down when no server answers or none has the model; having problems when one of several
 * does not. No document is sent: only the list of models is read. An API key is sent to the server and never shown.
 */
public final class ModelServerCheck implements DependencyCheck {

    static final String NAME = "service-model-server";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** One server: the address of its list of models, the model it must have (blank: any), its key (may be blank). */
    public record Server(URI models, String model, String apiKey) {
        @Override
        public String toString() {
            return "Server[" + shown(models) + "]";
        }
    }

    private final List<Server> servers;
    private final boolean required;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT).build();

    public ModelServerCheck(List<Server> servers, boolean required) {
        this.servers = List.copyOf(servers);
        this.required = required;
        if (servers.isEmpty()) throw new IllegalArgumentException("at least one model server");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String group() {
        return "services";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Duration timeout() {
        return TIMEOUT.multipliedBy(servers.size()).plusSeconds(2);
    }

    @Override
    public Verdict check() {
        Verdict firstBad = null;
        int good = 0;
        for (Server server : servers) {
            Verdict verdict = ask(server);
            if (verdict.code() == MonitoringCode.MON_WORKING) good++;
            else if (firstBad == null) firstBad = verdict;
        }
        if (firstBad == null) return Verdict.working();
        return good > 0 ? Verdict.problems(firstBad.code(), firstBad.details()) : firstBad;
    }

    private Verdict ask(Server server) {
        Map<String, String> where = Map.of("service", "model-server", "address", shown(server.models()),
                "timeout", TIMEOUT.toSeconds() + " s");
        URI address = server.models();
        if (address.getHost() == null || !("http".equalsIgnoreCase(address.getScheme())
                || "https".equalsIgnoreCase(address.getScheme()))) {
            return Verdict.down(MonitoringCode.MON_HTTP_ADDRESS_INVALID, where);
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(address).timeout(TIMEOUT).GET();
            if (server.apiKey() != null && !server.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + server.apiKey());
            }
            HttpResponse<String> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() != 200) {
                return Verdict.down(MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, Map.of("service", "model-server",
                        "address", shown(address), "status", String.valueOf(answer.statusCode()), "expected", "200"));
            }
            return hasModel(answer.body(), server.model()) ? Verdict.working()
                    : Verdict.down(new LvPlatformCode(LvErrorCode.AI_004));
        } catch (HttpTimeoutException e) {
            return Verdict.down(MonitoringCode.MON_HTTP_TIMEOUT, where);
        } catch (IOException e) {
            return Verdict.down(MonitoringCode.MON_HTTP_UNREACHABLE, where);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.down(MonitoringCode.MON_HTTP_TIMEOUT, where);
        }
    }

    /** Ollama lists {@code models[].name}, an OpenAI-compatible server {@code data[].id}. */
    private static boolean hasModel(String body, String model) {
        if (model == null || model.isBlank()) return true;
        try {
            JsonNode root = JsonMapper.builderWithJackson2Defaults().build().readTree(body);
            for (JsonNode m : root.path("models")) {
                if (Objects.equals(model, m.path("name").asString("")) || Objects.equals(model, m.path("model").asString(""))
                        || Objects.equals(model + ":latest", m.path("name").asString(""))) return true;
            }
            for (JsonNode m : root.path("data")) if (Objects.equals(model, m.path("id").asString(""))) return true;
            return false;
        } catch (RuntimeException notTheList) {
            return false;
        }
    }

    /** Never a user part or a query. */
    static String shown(URI u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }
}
