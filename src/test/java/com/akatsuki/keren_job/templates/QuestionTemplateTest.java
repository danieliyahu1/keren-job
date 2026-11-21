package com.akatsuki.keren_job.templates;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Field;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Option;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the question fragment with the same engine Spring Boot uses. MockMvc
 * never executes templates, so without this the only coverage of the form the
 * user actually sees would be at runtime.
 */
class QuestionTemplateTest {

    @Test
    @DisplayName("the question fragment renders the title, the fields and the answer action")
    void rendersTheQuestionForm() {
        Form form = new Form("form_1", "Which role?", List.of(
                new Field("role", "Role", "Pick one", "string",
                        List.of(new Option("backend", "Backend"), new Option("frontend", "Frontend")),
                        true, false),
                new Field("notes", "Notes", null, "string", List.of(), false, true)));

        String html = render(form);

        assertThat(html)
                .contains("Which role?")
                .contains("Pick one")
                .contains("hx-post=\"/api/search/answer\"")
                .contains("name=\"__formId\"")
                .contains("value=\"form_1\"")
                .contains("name=\"role\"")
                .contains("value=\"backend\"")
                .contains("name=\"notes\"");
    }

    private String render(Form form) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        Context context = new Context();
        context.setVariable("form", form);
        return engine.process("fragments/question", context);
    }
}
