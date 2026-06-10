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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

@Slf4j
@Primary
@Component
public class OpenCodeChatModel implements ChatModel {

    private final String baseUrl;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenCodeChatModel(
            @Value("${app.opencode.base-url}") String baseUrl,
            @Value("${app.opencode.timeout-seconds:600}") int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
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
        log.info("OpenCode creating new session via POST {}/session", baseUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = execute(request);
        log.debug("OpenCode POST /session → HTTP {}", response.statusCode());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode session creation failed HTTP {} body={}", response.statusCode(), response.body());
            throw new RuntimeException(
                    "OpenCode session creation failed with HTTP " + response.statusCode() +
                    ": " + response.body());
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            String sessionId = textOf(node, "id");
            if (sessionId == null || sessionId.isBlank()) {
                log.error("OpenCode session creation response missing 'id' field: {}", response.body());
                throw new RuntimeException(
                        "OpenCode session creation response missing 'id' field: " + response.body());
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/session/" + sessionId))
                    .DELETE()
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenCode DELETE /session/{} → HTTP {} (unexpected)",
                        sessionId, response.statusCode());
            } else {
                log.info("OpenCode session deleted sessionId='{}' HTTP {}", sessionId, response.statusCode());
            }
        } catch (Exception e) {
            log.warn("OpenCode session delete failed sessionId='{}': {}", sessionId, e.getMessage());
        }
    }

    private String sendMessage(String sessionId, String userText) {
        String body = buildPromptBody(userText);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();

        log.info("OpenCode POST /session/{}/message payloadLength={}", sessionId, body.length());

        HttpResponse<java.io.InputStream> response = executeWithRetry(request, HttpResponse.BodyHandlers.ofInputStream());

        log.debug("OpenCode POST /session/{}/message → HTTP {}", sessionId, response.statusCode());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenCode message returned HTTP {} for sessionId='{}'", response.statusCode(), sessionId);
            throw new RuntimeException(
                    "OpenCode message returned HTTP " + response.statusCode() +
                    " for sessionId='" + sessionId + "'");
        }

        String text = readTextPartsFromStream(response.body(), sessionId);
        log.info("OpenCode stream complete sessionId='{}' responseLength={}", sessionId, text.length());
        return text;
    }

    private String readTextPartsFromStream(java.io.InputStream body, String sessionId) {
        StringBuilder text = new StringBuilder();
        int linesRead = 0;
        int textPartsAccumulated = 0;
        int linesSkipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                linesRead++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode parts = node.get("parts");
                    if (parts == null || !parts.isArray()) {
                        linesSkipped++;
                        continue;
                    }
                    for (JsonNode part : parts) {
                        String partType = textOf(part, "type");
                        if ("text".equals(partType)) {
                            String partText = textOf(part, "text");
                            if (partText != null) {
                                text.append(partText);
                                textPartsAccumulated++;
                            }
                        } else if ("step-finish".equals(partType)) {
                            log.debug("OpenCode step-finish received sessionId='{}' linesRead={} textParts={} accumulatedLength={}",
                                    sessionId, linesRead, textPartsAccumulated, text.length());
                            if (text.isEmpty()) {
                                log.warn("OpenCode step-finish with empty text sessionId='{}' — returning fallback message", sessionId);
                                return "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
                            }
                            return text.toString();
                        } else if (partType != null) {
                            log.debug("OpenCode skipping part type='{}' sessionId='{}'", partType, sessionId);
                        }
                    }
                } catch (JsonProcessingException e) {
                    linesSkipped++;
                    log.trace("OpenCode unparseable NDJSON line sessionId='{}' line={}: {}",
                            sessionId, linesRead, line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading OpenCode response stream for session='" + sessionId + "'", e);
        }

        log.debug("OpenCode stream ended sessionId='{}' linesRead={} textParts={} linesSkipped={} accumulatedLength={}",
                sessionId, linesRead, textPartsAccumulated, linesSkipped, text.length());

        if (text.isEmpty()) {
            log.warn("OpenCode stream ended with no text parts sessionId='{}' linesRead={} — returning fallback message",
                    sessionId, linesRead);
            return "I was unable to complete this task. Please try breaking it into smaller steps or provide more details.";
        }
        return text.toString();
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
        log.error("Failed to connect to OpenCode gateway at {} after {} attempts",
                baseUrl, maxRetries, lastException);
        throw new RuntimeException(
                "Failed to connect to OpenCode gateway at " + baseUrl + " after " + maxRetries + " attempts: "
                        + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private String buildPromptBody(String userText) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("parts", List.of(
                            Map.of("type", "text", "text", userText == null ? "" : userText)
                    ))
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OpenCode prompt payload", e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
