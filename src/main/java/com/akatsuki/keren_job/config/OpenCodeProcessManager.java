package com.akatsuki.keren_job.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OpenCodeProcessManager {

    private final boolean autoStart;
    private final String command;
    private final List<String> args;
    private final Path workingDirectory;
    private final int startupTimeoutSeconds;
    private final String baseUrl;

    private Process process;

    public OpenCodeProcessManager(
            @Value("${app.opencode.auto-start:true}") boolean autoStart,
            @Value("${app.opencode.command:opencode}") String command,
            @Value("${app.opencode.args:serve,--port,4096}") String args,
            @Value("${app.opencode.working-directory:opencode-runtime}") String workingDirectory,
            @Value("${app.opencode.startup-timeout-seconds:30}") int startupTimeoutSeconds,
            @Value("${app.opencode.base-url:http://localhost:4096}") String baseUrl) {
        this.autoStart = autoStart;
        this.command = command;
        this.args = parseArgs(args);
        this.workingDirectory = Path.of(workingDirectory).toAbsolutePath().normalize();
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    void start() {
        if (!autoStart) {
            log.info("OpenCode auto-start disabled (app.opencode.auto-start=false)");
            return;
        }

        log.info("Starting OpenCode gateway: command='{}' args={} workingDir='{}'",
                command, args, workingDirectory);

        if (!workingDirectory.toFile().isDirectory()) {
            log.error("OpenCode working directory does not exist: '{}'", workingDirectory);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            Thread outputReader = new Thread(this::readProcessOutput, "opencode-stdout");
            outputReader.setDaemon(true);
            outputReader.start();

            log.info("OpenCode process started pid={}", process.pid());
        } catch (IOException e) {
            log.error("Failed to start OpenCode process: command='{}' args={} error={}",
                    command, args, e.getMessage(), e);
            return;
        }

        log.info("Waiting for OpenCode gateway (timeout={}s)...", startupTimeoutSeconds);
        boolean ready = waitForReady();
        if (ready) {
            log.info("OpenCode gateway is ready at {}", baseUrl);
        } else {
            log.error("OpenCode gateway did not become ready within {}s at {}",
                    startupTimeoutSeconds, baseUrl);
        }
    }

    @PreDestroy
    void stop() {
        if (process == null || !process.isAlive()) {
            log.info("OpenCode process already stopped");
            return;
        }
        log.info("Stopping OpenCode gateway pid={}", process.pid());
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            boolean terminated = process.waitFor(10, TimeUnit.SECONDS);
            if (!terminated) {
                log.warn("OpenCode process did not terminate gracefully, force-killing pid={}", process.pid());
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.info("OpenCode gateway stopped pid={}", process.pid());
    }

    private boolean waitForReady() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(startupTimeoutSeconds);
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/global/health"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    log.info("OpenCode health check passed on attempt {} (HTTP {})", attempt, response.statusCode());
                    return true;
                }
                log.debug("OpenCode health check attempt {} returned HTTP {}", attempt, response.statusCode());
            } catch (Exception e) {
                log.info("OpenCode health check attempt {} failed: {}", attempt, e.getMessage());
            }

            if (process != null && !process.isAlive()) {
                log.error("OpenCode process exited unexpectedly on attempt {} with code {}", attempt, process.exitValue());
                return false;
            }

            try {
                Thread.sleep(1000L * Math.min(attempt, 5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.error("OpenCode health check exhausted {} attempts over {}s",
                attempt, startupTimeoutSeconds);
        return false;
    }

    private void readProcessOutput() {
        log.info("OpenCode output reader started pid={}", process.pid());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[opencode] {}", line);
            }
        } catch (IOException e) {
            if (!"Stream closed".equals(e.getMessage())) {
                log.debug("OpenCode process output stream ended: {}", e.getMessage());
            }
        }
    }

    private static List<String> parseArgs(String args) {
        List<String> result = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return result;
        }
        for (String part : args.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
