package com.akatsuki.keren_job.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class HomeControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(thymeleafTemplateEngine());
        viewResolver.setCharacterEncoding("UTF-8");

        mockMvc = MockMvcBuilders.standaloneSetup(new HomeController())
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

    @Test
    @DisplayName("GET / returns HTTP 200 and index view")
    void getIndexReturns200AndIndexView() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @DisplayName("GET / renders form with HTMX attributes targeting #results")
    void getIndexRendersFormWithHtmxAttributes() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("hx-post=\"/api/search\"")))
                .andExpect(content().string(containsString("hx-target=\"#results\"")))
                .andExpect(content().string(containsString("hx-swap=\"innerHTML\"")))
                .andExpect(content().string(containsString("hx-indicator=\"#loading\"")))
                .andExpect(content().string(containsString("name=\"query\"")));
    }

    @Test
    @DisplayName("GET / renders loading indicator and results container")
    void getIndexRendersLoadingAndResultsElements() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("id=\"loading\"")))
                .andExpect(content().string(containsString("id=\"results\"")))
                .andExpect(content().string(containsString("id=\"validation-message\"")));
    }

    @Test
    @DisplayName("GET / textarea has autofocus attribute")
    void textareaHasAutofocus() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("autofocus")));
    }

    @Test
    @DisplayName("GET / renders client-side validation and request-state scripts")
    void rendersInlineScripts() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("htmx:beforeRequest")))
                .andExpect(content().string(containsString("htmx:afterRequest")))
                .andExpect(content().string(containsString("validation-message")));
    }
}
