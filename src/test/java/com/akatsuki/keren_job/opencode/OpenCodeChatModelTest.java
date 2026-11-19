package com.akatsuki.keren_job.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeChatModelTest {

    private HttpServer server;
    private OpenCodeChatModel chatModel;
    private final List<String> promptBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger messageRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/session", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("POST".equals(method) && path.equals("/api/session")) {
                send(exchange, 200, "{\"data\":{\"id\":\"ses_test\"}}");
                return;
            }
            if ("DELETE".equals(method) && path.equals("/api/session/ses_test")) {
                send(exchange, 204, "");
                return;
            }
            if ("POST".equals(method) && path.equals("/api/session/ses_test/prompt")) {
                promptBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(exchange, 200, "{\"data\":{}}");
                return;
            }
            if ("GET".equals(method) && path.equals("/api/session/ses_test/message")) {
                int request = messageRequests.incrementAndGet();
                if (request == 1) {
                    send(exchange, 200, "{\"data\":[],\"cursor\":{\"previous\":null,\"next\":null}}");
                } else {
                    send(exchange, 200, """
                            {"data":[{"id":"msg_1","type":"assistant","finish":"stop",
                            "content":[{"type":"reasoning","text":"hidden"},{"type":"text","text":"found jobs"}]}],
                            "cursor":{"previous":null,"next":null}}
                            """);
                }
                return;
            }
            send(exchange, 404, "{}");
        });

        server.createContext("/api/experimental/session/ses_test/wait", exchange -> send(exchange, 204, ""));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        chatModel = new OpenCodeChatModel(baseUrl, 5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("chat sends the prompt and returns the finished assistant text")
    void chatReturnsFinishedAssistantText() {
        String response = chatModel.chat("ses_test", "hello");

        assertThat(response).isEqualTo("found jobs");
        assertThat(promptBodies).hasSize(1);
        assertThat(promptBodies.get(0)).contains("\"text\":\"hello\"");
    }

    @Test
    @DisplayName("call creates a session, prompts it, and deletes it")
    void callCreatesAndDeletesSession() {
        var response = chatModel.call(
                new org.springframework.ai.chat.prompt.Prompt("hello"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("found jobs");
        assertThat(promptBodies).hasSize(1);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
