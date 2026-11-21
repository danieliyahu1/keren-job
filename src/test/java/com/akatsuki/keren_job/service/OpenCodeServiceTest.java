package com.akatsuki.keren_job.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenCodeServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private OpenCodeService openCodeService;

    @BeforeEach
    void setUp() {
        openCodeService = new OpenCodeService(chatClient);
    }

    private void stubChatClient(String responseContent) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(responseContent);
    }

    @Test
    @DisplayName("search returns raw response string from chat client")
    void searchReturnsRawResponse() {
        stubChatClient("some agent response text");

        String result = openCodeService.search("senior developer");

        assertThat(result).isEqualTo("some agent response text");
    }

    @Test
    @DisplayName("search returns empty string when agent responds with empty")
    void searchReturnsEmptyResponse() {
        stubChatClient("");

        String result = openCodeService.search("any query");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("search returns whatever the agent returns, unchanged")
    void searchPassesThroughResponseUnchanged() {
        stubChatClient("[]");

        String result = openCodeService.search("nonexistent role");

        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("search throws IllegalArgumentException when query is blank")
    void searchThrowsOnBlankQuery() {
        assertThatThrownBy(() -> openCodeService.search("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("search prompt targets the hiremetech skill and no other platform")
    void searchPromptTargetsHiremetechOnly() {
        stubChatClient("some agent response text");

        openCodeService.search("java developer in Tel Aviv");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(prompt.capture());
        assertThat(prompt.getValue())
                .contains("hiremetech-jobs")
                .contains("java developer in Tel Aviv")
                .doesNotContainIgnoringCase("linkedin");
    }
}
