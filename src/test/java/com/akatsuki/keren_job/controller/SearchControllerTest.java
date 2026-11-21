package com.akatsuki.keren_job.controller;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Field;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Option;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Outcome;
import com.akatsuki.keren_job.service.OpenCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private OpenCodeService openCodeService;

    private MockHttpSession session;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(openCodeService)).build();
    }

    private static Form form() {
        return new Form("form_1", "Which role?", List.of(
                new Field("role", "Role", "Pick one", "string",
                        List.of(new Option("dev", "Developer")), true, false)));
    }

    @Nested
    @DisplayName("POST /api/search")
    class PostSearch {

        @Test
        @DisplayName("returns the response fragment when the agent answers")
        void answerReturnsResponseFragment() throws Exception {
            when(openCodeService.search(any(), anyString())).thenReturn(new Outcome.Answer("some agent response"));

            mockMvc.perform(post("/api/search").session(session).param("query", "senior developer"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/response :: response"))
                    .andExpect(model().attribute("responseText", "some agent response"));
        }

        @Test
        @DisplayName("returns the question fragment with the form model attribute")
        void questionReturnsQuestionFragment() throws Exception {
            Form form = form();
            when(openCodeService.search(any(), anyString())).thenReturn(new Outcome.Question(form));

            mockMvc.perform(post("/api/search").session(session).param("query", "senior developer"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/question :: question"))
                    .andExpect(model().attribute("form", form));
        }

        @Test
        @DisplayName("returns the error fragment when the query is blank")
        void blankQueryReturnsErrorFragment() throws Exception {
            mockMvc.perform(post("/api/search").session(session).param("query", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/error :: error"))
                    .andExpect(model().attributeExists("message"));

            verifyNoInteractions(openCodeService);
        }

        @Test
        @DisplayName("returns the error fragment when another tab is already searching")
        void busySearchReturnsErrorFragment() throws Exception {
            when(openCodeService.search(any(), anyString()))
                    .thenThrow(new IllegalStateException("A search is already running in another tab."));

            mockMvc.perform(post("/api/search").session(session).param("query", "developer"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/error :: error"))
                    .andExpect(model().attribute("message", "A search is already running in another tab."));
        }

        @Test
        @DisplayName("passes the search to the service once")
        void delegatesToService() throws Exception {
            when(openCodeService.search(any(), anyString())).thenReturn(new Outcome.Answer("ok"));

            mockMvc.perform(post("/api/search").session(session).param("query", "dev"))
                    .andExpect(status().isOk());

            verify(openCodeService).search(session, "dev");
        }
    }

    @Nested
    @DisplayName("POST /api/search/answer")
    class PostAnswer {

        @Test
        @DisplayName("returns the response fragment when the agent answers after the reply")
        void answerReturnsResponseFragment() throws Exception {
            when(openCodeService.answer(any(), any())).thenReturn(new Outcome.Answer("here are jobs"));

            mockMvc.perform(post("/api/search/answer")
                            .session(session)
                            .param("__formId", "form_1")
                            .param("role", "dev"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/response :: response"))
                    .andExpect(model().attribute("responseText", "here are jobs"));
        }

        @Test
        @DisplayName("returns the next question fragment when the agent asks again")
        void nextQuestionReturnsQuestionFragment() throws Exception {
            Form form = form();
            when(openCodeService.answer(any(), any())).thenReturn(new Outcome.Question(form));

            mockMvc.perform(post("/api/search/answer").session(session).param("role", "dev"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/question :: question"))
                    .andExpect(model().attribute("form", form));
        }

        @Test
        @DisplayName("maps form values and ignores the __formId parameter")
        @SuppressWarnings("unchecked")
        void mapsFormValues() throws Exception {
            when(openCodeService.answer(any(), any())).thenReturn(new Outcome.Answer("ok"));

            mockMvc.perform(post("/api/search/answer")
                            .session(session)
                            .param("__formId", "form_1")
                            .param("role", "dev")
                            .param("skills", "java", "kotlin")
                            .param("remote", "on"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(openCodeService).answer(any(), captor.capture());
            assertThat(captor.getValue()).containsOnly(
                    Map.entry("role", "dev"),
                    Map.entry("skills", List.of("java", "kotlin")),
                    Map.entry("remote", Boolean.TRUE));
        }

        @Test
        @DisplayName("a custom answer overrides the selected option and is not sent as a field")
        void customAnswerOverridesSelectedOption() throws Exception {
            Form form = new Form("form_1", "Which role?", List.of(
                    new Field("role", "Role", null, "string",
                            List.of(new Option("dev", "Developer")), false, true)));
            when(openCodeService.pendingForm(any())).thenReturn(form);
            when(openCodeService.answer(any(), any())).thenReturn(new Outcome.Answer("ok"));

            mockMvc.perform(post("/api/search/answer")
                            .session(session)
                            .param("__formId", "form_1")
                            .param("role", "dev")
                            .param("__custom_role", "DevOps engineer"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(openCodeService).answer(any(), captor.capture());
            assertThat(captor.getValue())
                    .containsEntry("role", "DevOps engineer")
                    .doesNotContainKey("__custom_role");
        }

        @Test
        @DisplayName("returns the error fragment when there is no pending question")
        void noQuestionReturnsErrorFragment() throws Exception {
            when(openCodeService.answer(any(), any()))
                    .thenThrow(new IllegalStateException("There is no question to answer."));

            mockMvc.perform(post("/api/search/answer").session(session).param("role", "dev"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/error :: error"))
                    .andExpect(model().attribute("message", "There is no question to answer."));
        }
    }
}
