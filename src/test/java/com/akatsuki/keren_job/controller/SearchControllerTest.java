package com.akatsuki.keren_job.controller;

import com.akatsuki.keren_job.service.OpenCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private OpenCodeService openCodeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(thymeleafTemplateEngine());
        viewResolver.setCharacterEncoding("UTF-8");

        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(openCodeService))
                .setViewResolvers(viewResolver)
                .build();
    }

    private SpringTemplateEngine thymeleafTemplateEngine() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("/templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templateResolver);
        return engine;
    }

    @Nested
    @DisplayName("POST /api/search")
    class PostSearch {

        @Test
        @DisplayName("returns response fragment with responseText in model when query is valid")
        void validQueryReturnsResponseFragment() throws Exception {
            when(openCodeService.search(anyString())).thenReturn("some agent response");

            mockMvc.perform(post("/api/search").param("query", "senior developer"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/response :: response"))
                    .andExpect(model().attributeExists("responseText"));
        }

        @Test
        @DisplayName("returns error fragment when query is blank")
        void blankQueryReturnsErrorFragment() throws Exception {
            mockMvc.perform(post("/api/search").param("query", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/error :: error"))
                    .andExpect(model().attributeExists("message"));

            verifyNoInteractions(openCodeService);
        }

        @Test
        @DisplayName("returns error fragment when service throws")
        void serviceExceptionReturnsErrorFragment() throws Exception {
            when(openCodeService.search(anyString()))
                    .thenThrow(new RuntimeException("Search engine unavailable"));

            mockMvc.perform(post("/api/search").param("query", "developer"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("fragments/error :: error"))
                    .andExpect(model().attributeExists("message"));
        }

        @Test
        @DisplayName("renders response text in HTML output")
        void rendersResponseTextInHtml() throws Exception {
            when(openCodeService.search(anyString())).thenReturn("Hello from agent");

            mockMvc.perform(post("/api/search").param("query", "senior dev"))
                    .andExpect(content().string(containsString("Hello from agent")));
        }

        @Test
        @DisplayName("renders empty response without error")
        void emptyResponseRenderedAsEmpty() throws Exception {
            when(openCodeService.search(anyString())).thenReturn("");

            mockMvc.perform(post("/api/search").param("query", "whatever"))
                    .andExpect(view().name("fragments/response :: response"))
                    .andExpect(model().attributeExists("responseText"));
        }
    }
}
