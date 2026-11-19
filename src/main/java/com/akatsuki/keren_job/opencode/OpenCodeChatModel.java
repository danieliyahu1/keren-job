package com.akatsuki.keren_job.opencode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Chat model backed by the OpenCode V2 server API.
 *
 * <p>V2 prompts are asynchronous: {@code POST /api/session/{id}/prompt} only admits
 * the input. The agent loop then runs in the background, so this client waits for
 * the session to become idle and reads the newest assistant message.</p>
 */
@Slf4j
@Primary
@Component
public class OpenCodeChatModel implements ChatModel {

    private static final String FALLBACK_RESPONSE =
            "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
    private static final long POLL_INTERVAL_MS = 500L;

    private final String baseUrl;
    private final int timeoutSeconds;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenCodeChatModel(
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
        log.info("OpenCodeChatModel initialized baseUrl='{}' timeoutSeconds={}", baseUrl, timeoutSeconds);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = prompt.getContents();
        log.info("OpenCode stateless call — creating fresh session, messageLength={}",
                userText == null ? 0 : userText.length());

        String sessionId = createSession();
        try {
            String response = sendMessage(sessionId, userText);
            log.info("OpenCode stateless call completed sessionId='{}' responseLength={}", sessionId, response.length());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        } finally {
            deleteSession(sessionId);
        }
    }

    public String chat(String openCodeSessionId, String message) {
        if (openCodeSessionId == null || openCodeSessionId.isBlank()) {
            throw new IllegalArgumentException("openCodeSessionId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        log.info("OpenCode stateful chat — sessionId='{}' messageLength={}", openCodeSessionId, message.length());
        return sendMessage(openCodeSessionId, message);
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
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to parse OpenCode session creation response: " + response.body(), e);
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

    private String sendMessage(String sessionId, String userText) {
        String previousMessageId = latestAssistantMessageId(sessionId);
        String body = buildPromptBody(userText);
        HttpRequest request = requestBuilder("/api/session/" + sessionId + "/prompt")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode POST /api/session/{}/prompt payloadLength={}", sessionId, body.length());

        HttpResponse<String> response = execute(request);

        log.debug("OpenCode POST /api/session/{}/prompt → HTTP {}", sessionId, response.statusCode());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode prompt returned HTTP {} for sessionId='{}' body={}",
                    response.statusCode(), sessionId, response.body());
            throw new RuntimeException(
                    "OpenCode prompt returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "'");
        }

        JsonNode message = awaitAssistantMessage(sessionId, previousMessageId);
        String text = extractText(message);
        log.info("OpenCode prompt complete sessionId='{}' responseLength={}", sessionId, text.length());
        return text;
    }

    /**
     * Waits until an assistant message newer than {@code previousMessageId} has finished.
     * The server exposes a "wait for idle" endpoint, but it can return before the queued
     * prompt starts, so the message state is polled as the source of truth.
     */
    private JsonNode awaitAssistantMessage(String sessionId, String previousMessageId) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            waitForIdle(sessionId);

            JsonNode message = latestAssistantMessage(sessionId);
            if (message != null) {
                String messageId = textOf(message, "id");
                boolean isNew = previousMessageId == null || !previousMessageId.equals(messageId);
                boolean finished = message.hasNonNull("finish")
                        || message.path("time").hasNonNull("completed");
                if (isNew && finished) {
                    return message;
                }
            }

            sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException(
                "OpenCode session '" + sessionId + "' did not finish within " + timeoutSeconds + "s");
    }

    private void waitForIdle(String sessionId) {
        HttpRequest request = requestBuilder("/api/experimental/session/" + sessionId + "/wait")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        try {
            HttpResponse<String> response = execute(request);
            log.trace("OpenCode POST /api/experimental/session/{}/wait → HTTP {}",
                    sessionId, response.statusCode());
        } catch (RuntimeException e) {
            log.debug("OpenCode session wait failed sessionId='{}': {}", sessionId, e.getMessage());
        }
    }

    private JsonNode latestAssistantMessage(String sessionId) {
        HttpRequest request = requestBuilder("/api/session/" + sessionId
                        + "/message?type=assistant&order=desc&limit=1")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = execute(request);
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
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse OpenCode messages for sessionId='{}': {}", sessionId, e.getMessage());
            return null;
        }
    }

    private String latestAssistantMessageId(String sessionId) {
        JsonNode message = latestAssistantMessage(sessionId);
        return message == null ? null : textOf(message, "id");
    }

    private String extractText(JsonNode message) {
        if (message != null && message.hasNonNull("error")) {
            log.warn("OpenCode assistant message finished with error: {}", message.path("error"));
        }

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

        if (text.isEmpty()) {
            log.warn("OpenCode assistant message contained no text — returning fallback message");
            return FALLBACK_RESPONSE;
        }
        return text.toString();
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

    private String buildPromptBody(String userText) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("text", userText == null ? "" : userText)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OpenCode prompt payload", e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
