package com.akatsuki.keren_job.controller;

import com.akatsuki.keren_job.service.OpenCodeService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;

@Slf4j
@Controller
class SearchController {

    private final OpenCodeService openCodeService;

    SearchController(OpenCodeService openCodeService) {
        this.openCodeService = openCodeService;
    }

    @PostMapping("/api/search")
    String search(@Valid SearchRequest request, BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("message", result.getFieldError().getDefaultMessage());
            return "fragments/error :: error";
        }

        String query = request.query().trim();
        log.info("Search requested: query='{}'", query);

        try {
            String response = openCodeService.search(query);
            model.addAttribute("responseText", response);
            return "fragments/response :: response";
        } catch (Exception e) {
            log.error("Search failed for query='{}'", query, e);
            model.addAttribute("message", e.getMessage());
            return "fragments/error :: error";
        }
    }
}
