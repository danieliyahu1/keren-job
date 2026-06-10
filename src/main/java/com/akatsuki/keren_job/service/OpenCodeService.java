package com.akatsuki.keren_job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OpenCodeService {

    private final ChatClient chatClient;

    public OpenCodeService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String search(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        String prompt = buildSearchPrompt(query);
        return callOpenCode(prompt);
    }

    private String buildSearchPrompt(String query) {
        return """
            Use the linkedin-jobs skill to search for jobs matching the following description.

            Query: %s

            """.formatted(query);
    }

    private String callOpenCode(String prompt) {
        log.info("Sending prompt ({} chars): {}", prompt.length(), prompt);
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        log.info("Received response ({} chars): {}", response == null ? 0 : response.length(), response);
        return response;
    }
}
