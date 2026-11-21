package com.akatsuki.keren_job.opencode;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Outcome;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeAgentClientTest {

    private HttpServer server;
    private OpenCodeAgentClient client;

    private final List<String> promptBodies = new CopyOnWriteArrayList<>();
    private final List<String> formReplyBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

    private volatile String createdSessionId = "ses_test";
    private volatile int promptStatus = 200;
    private volatile boolean messagesAvailable;
    private volatile boolean formPending;
    private volatile String formJson = "{}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new OpenCodeAgentClient(baseUrl, 5, "testpass");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("createSession, prompt and a finished assistant message yield the answer text")
    void createPromptAndReadFinishedMessage() {
        String sessionId = client.createSession();

        String baseline = client.latestAssistantMessageId(sessionId);
        assertThat(baseline).isNull();

        client.prompt(sessionId, "hello");
        messagesAvailable = true;

        Outcome outcome = client.await(sessionId, baseline);

        assertThat(outcome).isInstanceOf(Outcome.Answer.class);
        assertThat(((Outcome.Answer) outcome).text()).isEqualTo("found jobs");
        assertThat(promptBodies).hasSize(1);
        assertThat(promptBodies.get(0)).contains("\"text\":\"hello\"");
        assertThat(authorizationHeaders).isNotEmpty().allMatch(this::isExpectedAuthorization);
    }

    @Test
    @DisplayName("a pending form surfaces as a Question with its fields and options")
    void pendingFormSurfacesAsQuestion() {
        formPending = true;
        formJson = """
                {"id":"form_1","title":"Which role?","fields":[
                  {"key":"role","title":"Role","description":"Pick one","type":"string",
                   "options":[{"value":"dev","label":"Developer"}],"required":true,"custom":false},
                  {"key":"remote","title":"Remote only","type":"boolean","required":false,"custom":false}
                ]}
                """;

        Outcome outcome = client.await("ses_test", null);

        assertThat(outcome).isInstanceOf(Outcome.Question.class);
        Form form = ((Outcome.Question) outcome).form();
        assertThat(form.id()).isEqualTo("form_1");
        assertThat(form.title()).isEqualTo("Which role?");
        assertThat(form.fields()).hasSize(2);

        var role = form.fields().get(0);
        assertThat(role.key()).isEqualTo("role");
        assertThat(role.type()).isEqualTo("string");
        assertThat(role.description()).isEqualTo("Pick one");
        assertThat(role.required()).isTrue();
        assertThat(role.custom()).isFalse();
        assertThat(role.options()).containsExactly(new OpenCodeAgentClient.Option("dev", "Developer"));

        assertThat(form.fields().get(1).type()).isEqualTo("boolean");
        assertThat(form.fields().get(1).options()).isEmpty();
    }

    @Test
    @DisplayName("a form reply is posted and the following finished message is returned")
    void replyThenFinishedMessage() {
        client.prompt("ses_test", "find jobs");
        client.reply("ses_test", "form_1", Map.of("role", "dev", "remote", true));

        assertThat(formReplyBodies).hasSize(1);
        assertThat(formReplyBodies.get(0))
                .contains("\"answer\"")
                .contains("\"role\":\"dev\"")
                .contains("\"remote\":true");

        messagesAvailable = true;

        assertThat(client.await("ses_test", null)).isInstanceOf(Outcome.Answer.class);
    }

    @Test
    @DisplayName("a 404 on prompt signals that the session must be recreated")
    void prompt404SignalsRecreate() {
        promptStatus = 404;

        assertThatThrownBy(() -> client.prompt("ses_test", "hello"))
                .isInstanceOf(OpenCodeAgentClient.SessionNotFoundException.class);

        createdSessionId = "ses_new";
        promptStatus = 200;

        String sessionId = client.createSession();
        String baseline = client.latestAssistantMessageId(sessionId);
        client.prompt(sessionId, "hello again");
        messagesAvailable = true;

        assertThat(sessionId).isEqualTo("ses_new");
        assertThat(client.await(sessionId, baseline)).isInstanceOf(Outcome.Answer.class);
        assertThat(promptBodies).hasSize(2);
    }

    private boolean isExpectedAuthorization(String header) {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("opencode:testpass".getBytes(StandardCharsets.UTF_8));
        return expected.equals(header);
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("POST".equals(method) && path.equals("/api/session")) {
            send(exchange, 200, "{\"data\":{\"id\":\"" + createdSessionId + "\"}}");
            return;
        }
        if ("POST".equals(method) && path.endsWith("/prompt")) {
            promptBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, promptStatus, promptStatus == 404 ? "{}" : "{\"data\":{}}");
            return;
        }
        if ("GET".equals(method) && path.endsWith("/form")) {
            if (formPending) {
                send(exchange, 200, "{\"data\":[" + formJson + "]}");
            } else {
                send(exchange, 200, "{\"data\":[]}");
            }
            return;
        }
        if ("POST".equals(method) && path.contains("/form/") && path.endsWith("/reply")) {
            formReplyBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            formPending = false;
            send(exchange, 204, "");
            return;
        }
        if ("GET".equals(method) && path.endsWith("/message")) {
            if (messagesAvailable) {
                send(exchange, 200, """
                        {"data":[{"id":"msg_1","type":"assistant","finish":"stop",
                        "content":[{"type":"reasoning","text":"hidden"},{"type":"text","text":"found "},
                        {"type":"text","text":"jobs"}],"time":{"completed":1}}],
                        "cursor":{"previous":null,"next":null}}
                        """);
            } else {
                send(exchange, 200, "{\"data\":[],\"cursor\":{\"previous\":null,\"next\":null}}");
            }
            return;
        }
        if ("POST".equals(method) && path.endsWith("/wait")) {
            send(exchange, 204, "");
            return;
        }
        send(exchange, 404, "{}");
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
