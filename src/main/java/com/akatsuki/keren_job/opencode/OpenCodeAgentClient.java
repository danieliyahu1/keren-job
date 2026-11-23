package com.akatsuki.keren_job.opencode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Client for the OpenCode V2 server API.
 *
 * <p>V2 prompts are asynchronous: {@code POST /api/session/{id}/prompt} only admits
 * the input. The agent loop then runs in the background, so this client waits for
 * the session to become idle and either reads the newest assistant message or
 * surfaces a question the agent asked through the {@code question} tool.</p>
 */
@Slf4j
@Component
public class OpenCodeAgentClient {

    private static final long POLL_INTERVAL_MS = 500L;

    private final String baseUrl;
    private final int timeoutSeconds;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenCodeAgentClient(
            @Value("${app.opencode.base-url}") String baseUrl,
            @Value("${app.opencode.timeout-seconds:600}") int timeoutSeconds,
            @Value("${app.opencode.password:}") String password) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("OpenCodeAgentClient initialized baseUrl='{}' timeoutSeconds={}", baseUrl, timeoutSeconds);
    }

    /** Result of a completed turn: the agent's answer, a question, or a turn that failed. */
    public sealed interface Outcome {
        record Answer(String text) implements Outcome {}
        record Question(Form form) implements Outcome {}
        /** The turn ended with an error from OpenCode or the model. {@code detail} is the raw error. */
        record Error(String detail) implements Outcome {}
    }

    public record Form(String id, String title, List<Field> fields) {}

    public record Field(
            String key,
            String title,
            String description,
            String type,
            List<Option> options,
            boolean required,
            boolean custom) {}

    public record Option(String value, String label) {}

    /** Signals that the sidecar no longer knows the session, so the caller should recreate it. */
    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) {
            super(message);
        }
    }

    public String createSession() {
        log.info("OpenCode creating new session via POST {}/api/session", baseUrl);
        HttpRequest request = requestBuilder("/api/session")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = execute(request);
        log.debug("OpenCode POST /api/session → HTTP {}", response.statusCode());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode session creation failed HTTP {} body={}", response.statusCode(), response.body());
            throw new RuntimeException(
                    "OpenCode session creation failed with HTTP " + response.statusCode() +
                    ": " + response.body());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            String sessionId = textOf(root.path("data"), "id");
            if (sessionId == null || sessionId.isBlank()) {
                log.error("OpenCode session creation response missing 'data.id' field: {}", response.body());
                throw new RuntimeException(
                        "OpenCode session creation response missing 'data.id' field: " + response.body());
            }
            log.info("OpenCode session created sessionId='{}'", sessionId);
            return sessionId;
        } catch (JacksonException e) {
            throw new RuntimeException(
                    "Failed to parse OpenCode session creation response: " + response.body(), e);
        }
    }

    public void prompt(String sessionId, String text) {
        String body = buildJson(Map.of("text", text == null ? "" : text));
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/prompt")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode POST /api/session/{}/prompt payloadLength={}", sessionId, body.length());

        HttpResponse<String> response = execute(request);
        log.debug("OpenCode POST /api/session/{}/prompt → HTTP {}", sessionId, response.statusCode());

        if (response.statusCode() == 404) {
            throw new SessionNotFoundException("OpenCode session '" + sessionId + "' no longer exists");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode prompt returned HTTP {} for sessionId='{}' body={}",
                    response.statusCode(), sessionId, response.body());
            throw new RuntimeException(
                    "OpenCode prompt returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "': " + response.body());
        }
    }

    /**
     * Waits until the session needs the user again: a pending form yields a
     * {@link Outcome.Question}, the turn's final assistant step yields an
     * {@link Outcome.Answer}, and a step that failed at the provider yields an
     * {@link Outcome.Error} carrying the raw error.
     *
     * <p>A step that ends on a tool call ({@code finish=tool-calls}) is not the
     * answer even when it carries narration text: the agent loop continues with
     * the tool result and produces the real answer in a later step.</p>
     */
    public Outcome await(String sessionId, String baselineMessageId) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            Form form = firstPendingForm(sessionId);
            if (form != null) {
                log.info("OpenCode session '{}' is waiting for a form reply formId='{}'", sessionId, form.id());
                return new Outcome.Question(form);
            }

            JsonNode message = latestAssistantMessage(sessionId);
            if (message != null) {
                String messageId = textOf(message, "id");
                boolean isNew = baselineMessageId == null || !baselineMessageId.equals(messageId);
                boolean finished = message.hasNonNull("finish")
                        || message.path("time").hasNonNull("completed");
                boolean toolCallStep = isToolCallStep(message);
                if (isNew && finished && !toolCallStep) {
                    String text = textParts(message);
                    if (!text.isEmpty()) {
                        return new Outcome.Answer(text);
                    }
                    if (message.hasNonNull("error")) {
                        JsonNode error = message.path("error");
                        log.warn("OpenCode assistant message finished with error: {}", error);
                        return new Outcome.Error(error.toString());
                    }
                    log.debug("ignoring a finished message without text id='{}'", messageId);
                } else if (isNew && finished) {
                    // The `question` tool is one of these steps: skip it so the
                    // answer -> question -> result sequence lands on the text step.
                    log.debug("ignoring a tool-call step id='{}' finish='{}' until the turn's final step",
                            messageId, textOf(message, "finish"));
                }
            }

            waitForIdle(sessionId);
            sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException(
                "OpenCode session '" + sessionId + "' did not finish within " + timeoutSeconds + "s");
    }

    /** Returns the first pending form of the session, or {@code null} when the agent is not asking. */
    public Form firstPendingForm(String sessionId) {
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/form")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = execute(request);
        if (response.statusCode() == 404) {
            throw new SessionNotFoundException("OpenCode session '" + sessionId + "' no longer exists");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.debug("OpenCode GET /api/session/{}/form → HTTP {}", sessionId, response.statusCode());
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode forms = root.path("data");
            if (!forms.isArray() || forms.isEmpty()) {
                return null;
            }
            return parseForm(forms.get(0));
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenCode forms for sessionId='{}': {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Reads one form by id, or {@code null} when it is no longer pending. */
    public Form form(String sessionId, String formId) {
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/form/" + formId)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = execute(request);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.debug("OpenCode GET /api/session/{}/form/{} -> HTTP {}",
                    sessionId, formId, response.statusCode());
            return null;
        }

        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            return data.isMissingNode() || data.isNull() ? null : parseForm(data);
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenCode form '{}': {}", formId, e.getMessage());
            return null;
        }
    }

    /** Dismisses a pending form. Best effort: a form that is already gone is not an error. */
    public void cancelForm(String sessionId, String formId) {
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/form/" + formId)
                .DELETE()
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
        try {
            HttpResponse<String> response = execute(request);
            log.info("OpenCode DELETE /api/session/{}/form/{} -> HTTP {}",
                    sessionId, formId, response.statusCode());
        } catch (RuntimeException e) {
            log.warn("OpenCode form cancel failed sessionId='{}' formId='{}': {}",
                    sessionId, formId, e.getMessage());
        }
    }

    public void reply(String sessionId, String formId, Map<String, Object> answer) {
        String body = buildJson(Map.of("answer", answer == null ? Map.of() : answer));
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/form/" + formId + "/reply")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode POST /api/session/{}/form/{}/reply", sessionId, formId);

        HttpResponse<String> response = execute(request);
        log.debug("OpenCode POST /api/session/{}/form/{}/reply → HTTP {}",
                sessionId, formId, response.statusCode());

        if (response.statusCode() == 404) {
            throw new SessionNotFoundException("OpenCode session '" + sessionId + "' no longer exists");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode form reply returned HTTP {} for sessionId='{}' formId='{}' body={}",
                    response.statusCode(), sessionId, formId, response.body());
            throw new RuntimeException(
                    "OpenCode form reply returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "': " + response.body());
        }
    }

    public void deleteSession(String sessionId) {
        log.info("OpenCode deleting session sessionId='{}'", sessionId);
        try {
            HttpRequest request = requestBuilder("/api/session/" + sessionId)
                    .DELETE()
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenCode DELETE /api/session/{} → HTTP {} (unexpected)",
                        sessionId, response.statusCode());
            } else {
                log.info("OpenCode session deleted sessionId='{}' HTTP {}", sessionId, response.statusCode());
            }
        } catch (Exception e) {
            log.warn("OpenCode session delete failed sessionId='{}': {}", sessionId, e.getMessage());
        }
    }

    /** Returns the id of the newest assistant message, or {@code null} when the session has none. */
    public String latestAssistantMessageId(String sessionId) {
        JsonNode message = latestAssistantMessage(sessionId);
        return message == null ? null : textOf(message, "id");
    }

    private JsonNode latestAssistantMessage(String sessionId) {
        HttpRequest request = requestBuilder("/api/session/" + sessionId
                        + "/message?type=assistant&order=desc&limit=1")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = execute(request);
        if (response.statusCode() == 404) {
            throw new SessionNotFoundException("OpenCode session '" + sessionId + "' no longer exists");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.debug("OpenCode GET /api/session/{}/message → HTTP {}", sessionId, response.statusCode());
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode messages = root.path("data");
            if (!messages.isArray() || messages.isEmpty()) {
                return null;
            }
            return messages.get(0);
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenCode messages for sessionId='{}': {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void waitForIdle(String sessionId) {
        // A short timeout keeps the turn inside its own 600 s budget: `/wait` blocks
        // while the model is busy, so a stuck provider must return to the poll loop
        // instead of parking the request thread for the full timeout.
        HttpRequest request = requestBuilder("/api/experimental/session/" + sessionId + "/wait")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.trace("OpenCode POST /api/experimental/session/{}/wait → HTTP {}",
                    sessionId, response.statusCode());
        } catch (HttpTimeoutException e) {
            // Still busy: the caller's loop decides whether the turn is over budget.
            log.trace("OpenCode session '{}' is still busy", sessionId);
        } catch (IOException e) {
            log.debug("OpenCode session wait failed sessionId='{}': {}", sessionId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for OpenCode", e);
        }
    }

    private String textParts(JsonNode message) {
        StringBuilder text = new StringBuilder();
        JsonNode content = message == null ? null : message.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(textOf(part, "type"))) {
                    String partText = textOf(part, "text");
                    if (partText != null) {
                        text.append(partText);
                    }
                }
            }
        }
        return text.toString();
    }

    private Form parseForm(JsonNode node) {
        List<Field> fields = new ArrayList<>();
        JsonNode fieldNodes = node.path("fields");
        if (fieldNodes.isArray()) {
            for (JsonNode fieldNode : fieldNodes) {
                List<Option> options = new ArrayList<>();
                JsonNode optionNodes = fieldNode.path("options");
                if (optionNodes.isArray()) {
                    for (JsonNode optionNode : optionNodes) {
                        options.add(new Option(textOf(optionNode, "value"), textOf(optionNode, "label")));
                    }
                }
                fields.add(new Field(
                        textOf(fieldNode, "key"),
                        textOf(fieldNode, "title"),
                        textOf(fieldNode, "description"),
                        textOf(fieldNode, "type"),
                        List.copyOf(options),
                        fieldNode.path("required").asBoolean(false),
                        fieldNode.path("custom").asBoolean(false)));
            }
        }
        return new Form(textOf(node, "id"), textOf(node, "title"), List.copyOf(fields));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for OpenCode response", e);
        }
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (password != null && !password.isBlank()) {
            String credentials = "opencode:" + password;
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return builder;
    }

    private HttpResponse<String> execute(HttpRequest request) {
        return executeWithRetry(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> HttpResponse<T> executeWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        int maxRetries = 3;
        long backoffMs = 1000;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return httpClient.send(request, bodyHandler);
            } catch (IOException e) {
                lastException = e;
                boolean timeout = e instanceof HttpTimeoutException;
                if (timeout || attempt >= maxRetries) {
                    if (timeout) {
                        log.error("OpenCode request timed out after {}s attempt={}/{}",
                                timeoutSeconds, attempt, maxRetries);
                    }
                    break;
                }
                log.warn("OpenCode connection attempt {}/{} failed: {} — retrying in {}ms",
                        attempt, maxRetries, e.toString(), backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Failed to connect to OpenCode gateway at " + baseUrl + ": " + e.getMessage(), e);
                }
                backoffMs *= 2;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Failed to connect to OpenCode gateway at " + baseUrl + ": " + e.getMessage(), e);
            }
        }
        log.error("Failed to connect to OpenCode gateway at {} after {} attempts", baseUrl, maxRetries, lastException);
        throw new RuntimeException(
                "Failed to connect to OpenCode gateway at " + baseUrl + " after " + maxRetries + " attempts: "
                        + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private String buildJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize OpenCode payload", e);
        }
    }

    /** A step that stopped to run a tool; the turn continues with the tool result. */
    private static boolean isToolCallStep(JsonNode message) {
        return "tool-calls".equals(textOf(message, "finish"));
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
