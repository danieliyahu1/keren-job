package com.akatsuki.keren_job.service;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenCodeServiceTest {

    @Mock
    private OpenCodeAgentClient agent;

    private MockHttpSession session;
    private OpenCodeService openCodeService;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        openCodeService = new OpenCodeService(agent);
    }

    @Test
    @DisplayName("search sends the user text verbatim and stores the session state")
    void searchSendsRawText() {
        when(agent.createSession()).thenReturn("ses_1");
        when(agent.latestAssistantMessageId("ses_1")).thenReturn("msg_0");
        when(agent.await("ses_1", "msg_0")).thenReturn(new Outcome.Answer("some agent response text"));

        String raw = "  Find me \"java\" jobs\nin Tel Aviv  ";
        Outcome outcome = openCodeService.search(session, raw);

        assertThat(outcome).isEqualTo(new Outcome.Answer("some agent response text"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).prompt(eq("ses_1"), prompt.capture());
        assertThat(prompt.getValue()).isEqualTo(raw);

        assertThat(session.getAttribute(OpenCodeService.ATTR_SESSION_ID)).isEqualTo("ses_1");
        assertThat(session.getAttribute(OpenCodeService.ATTR_BASELINE)).isEqualTo("msg_0");
        assertThat(session.getAttribute(OpenCodeService.ATTR_FORM_ID)).isNull();
        assertThat(OpenCodeService.isBusy(session)).isFalse();
    }

    @Test
    @DisplayName("a Question outcome stores the pending form id")
    void questionStoresPendingFormId() {
        Form form = form("form_1");
        when(agent.createSession()).thenReturn("ses_1");
        when(agent.latestAssistantMessageId("ses_1")).thenReturn(null);
        when(agent.await("ses_1", null)).thenReturn(new Outcome.Question(form));

        Outcome outcome = openCodeService.search(session, "find jobs");

        assertThat(outcome).isEqualTo(new Outcome.Question(form));
        assertThat(OpenCodeService.pendingFormId(session)).isEqualTo("form_1");
        assertThat(OpenCodeService.isBusy(session)).isFalse();
    }

    @Test
    @DisplayName("the baseline is kept while answering the agent's question")
    void baselineIsKeptAfterAQuestion() {
        Form form = form("form_1");
        when(agent.createSession()).thenReturn("ses_1");
        when(agent.latestAssistantMessageId("ses_1")).thenReturn("msg_0");
        when(agent.await("ses_1", "msg_0"))
                .thenReturn(new Outcome.Question(form))
                .thenReturn(new Outcome.Answer("here are jobs"));

        openCodeService.search(session, "find jobs");
        assertThat(session.getAttribute(OpenCodeService.ATTR_BASELINE)).isEqualTo("msg_0");

        Outcome outcome = openCodeService.answer(session, Map.of("role", "dev"));

        assertThat(outcome).isEqualTo(new Outcome.Answer("here are jobs"));
        verify(agent).reply("ses_1", "form_1", Map.of("role", "dev"));
        verify(agent, times(1)).latestAssistantMessageId("ses_1");
        verify(agent, times(2)).await("ses_1", "msg_0");
        assertThat(session.getAttribute(OpenCodeService.ATTR_BASELINE)).isEqualTo("msg_0");
        assertThat(OpenCodeService.pendingFormId(session)).isNull();
    }

    @Test
    @DisplayName("the busy lock rejects a second search without touching the agent")
    void busyLockRejectsSecondSearch() {
        session.setAttribute(OpenCodeService.ATTR_BUSY, Boolean.TRUE);

        assertThatThrownBy(() -> openCodeService.search(session, "find jobs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A search is already running in another tab.");

        verifyNoInteractions(agent);
    }

    @Test
    @DisplayName("a pending question rejects another search")
    void pendingQuestionRejectsAnotherSearch() {
        session.setAttribute(OpenCodeService.ATTR_FORM_ID, "form_1");

        assertThatThrownBy(() -> openCodeService.search(session, "find jobs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A search is already running in another tab.");

        verifyNoInteractions(agent);
    }

    @Test
    @DisplayName("answering without a pending question is rejected")
    void answerWithoutPendingFormIsRejected() {
        assertThatThrownBy(() -> openCodeService.answer(session, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("There is no question to answer.");

        verifyNoInteractions(agent);
    }

    @Test
    @DisplayName("a vanished OpenCode session is recreated and the turn retried once")
    void vanishedSessionIsRecreatedOnce() {
        when(agent.createSession()).thenReturn("ses_old", "ses_new");
        when(agent.latestAssistantMessageId("ses_old")).thenReturn("msg_0");
        when(agent.latestAssistantMessageId("ses_new")).thenReturn(null);
        when(agent.await("ses_new", null)).thenReturn(new Outcome.Answer("recovered"));

        org.mockito.Mockito.doThrow(new OpenCodeAgentClient.SessionNotFoundException("gone"))
                .when(agent).prompt(eq("ses_old"), any());

        Outcome outcome = openCodeService.search(session, "find jobs");

        assertThat(outcome).isEqualTo(new Outcome.Answer("recovered"));
        verify(agent, times(2)).createSession();
        verify(agent).prompt("ses_new", "find jobs");
        assertThat(session.getAttribute(OpenCodeService.ATTR_SESSION_ID)).isEqualTo("ses_new");
    }

    @Test
    @DisplayName("search throws IllegalArgumentException when the query is blank")
    void searchThrowsOnBlankQuery() {
        assertThatThrownBy(() -> openCodeService.search(session, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        verifyNoInteractions(agent);
    }

    @Test
    @DisplayName("an expired session is replaced before the prompt is sent")
    void expiredSessionIsReplaced() {
        session.setAttribute(OpenCodeService.ATTR_SESSION_ID, "ses_stale");
        session.setAttribute(OpenCodeService.ATTR_LAST_USED, 0L);

        when(agent.createSession()).thenReturn("ses_new");
        when(agent.latestAssistantMessageId("ses_new")).thenReturn(null);
        when(agent.await("ses_new", null)).thenReturn(new Outcome.Answer("ok"));

        openCodeService.search(session, "find jobs");

        verify(agent).deleteSession("ses_stale");
        verify(agent).prompt("ses_new", "find jobs");
        verify(agent, never()).prompt(eq("ses_stale"), any());
    }

    private static Form form(String id) {
        return new Form(id, "Which role?", List.of(
                new OpenCodeAgentClient.Field("role", "Role", "Pick one", "string",
                        List.of(new OpenCodeAgentClient.Option("dev", "Developer")), true, false)));
    }
}
