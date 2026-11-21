package com.akatsuki.keren_job.controller;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Field;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Outcome;
import com.akatsuki.keren_job.service.OpenCodeService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
class SearchController {

    private static final String FORM_ID_PARAM = "__formId";
    private static final String CUSTOM_PREFIX = "__custom_";

    private final OpenCodeService openCodeService;

    SearchController(OpenCodeService openCodeService) {
        this.openCodeService = openCodeService;
    }

    @PostMapping("/api/search")
    String search(@Valid SearchRequest request, BindingResult result, HttpSession session, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("message", result.getFieldError().getDefaultMessage());
            return "fragments/error :: error";
        }

        String query = request.query();
        log.info("Search requested: queryLength={}", query.length());

        try {
            return render(openCodeService.search(session, query), model);
        } catch (Exception e) {
            log.error("Search failed", e);
            model.addAttribute("message", e.getMessage());
            return "fragments/error :: error";
        }
    }

    @PostMapping("/api/search/answer")
    String answer(@RequestParam MultiValueMap<String, String> fields, HttpSession session, Model model) {
        Map<String, Object> values = toAnswer(fields, openCodeService.pendingForm(session));
        log.info("Form answer submitted for keys={}", values.keySet());

        try {
            return render(openCodeService.answer(session, values), model);
        } catch (Exception e) {
            log.error("Form answer failed", e);
            model.addAttribute("message", e.getMessage());
            return "fragments/error :: error";
        }
    }

    private String render(Outcome outcome, Model model) {
        return switch (outcome) {
            case Outcome.Answer answer -> {
                model.addAttribute("responseText", answer.text());
                yield "fragments/response :: response";
            }
            case Outcome.Question question -> {
                model.addAttribute("form", question.form());
                yield "fragments/question :: question";
            }
        };
    }

    private static Map<String, Object> toAnswer(MultiValueMap<String, String> fields, Form form) {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((key, rawValues) -> {
            if (FORM_ID_PARAM.equals(key) || key.startsWith(CUSTOM_PREFIX)
                    || rawValues == null || rawValues.isEmpty()) {
                return;
            }
            if (rawValues.size() > 1) {
                values.put(key, new ArrayList<>(rawValues));
            } else {
                values.put(key, toScalar(rawValues.get(0)));
            }
        });

        if (form != null) {
            for (Field field : form.fields()) {
                String custom = firstNonBlank(fields.get(CUSTOM_PREFIX + field.key()));
                if (custom == null) {
                    continue;
                }
                if ("multiselect".equals(field.type())) {
                    List<String> combined = new ArrayList<>();
                    Object existing = values.get(field.key());
                    if (existing instanceof List<?> list) {
                        list.forEach(value -> combined.add(String.valueOf(value)));
                    } else if (existing != null) {
                        combined.add(String.valueOf(existing));
                    }
                    combined.add(custom);
                    values.put(field.key(), combined);
                } else {
                    values.put(field.key(), custom);
                }
            }
        }
        return values;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
    }

    private static Object toScalar(String value) {
        if ("on".equals(value) || "true".equals(value)) {
            return Boolean.TRUE;
        }
        return value;
    }
}
