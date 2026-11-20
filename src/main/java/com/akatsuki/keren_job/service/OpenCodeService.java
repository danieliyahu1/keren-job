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
            Search for jobs matching the description below.

            Platforms, in priority order:
            1. hiremetech-jobs - the default. Public JSON API, no login, no browser.
            2. linkedin-jobs - only when the query explicitly asks for LinkedIn, or when
               HireMe returns an error, nothing, or fewer than 3 matches.

            Return at most 3 strong matches. For each: title, company, location and work
            mode, why it matches, and the full link. State which platform each match came from.

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
