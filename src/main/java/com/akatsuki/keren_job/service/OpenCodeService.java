package com.akatsuki.keren_job.service;

import com.akatsuki.keren_job.opencode.OpenCodeAgentClient;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Form;
import com.akatsuki.keren_job.opencode.OpenCodeAgentClient.Outcome;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs search turns against a single long-lived OpenCode session per browser.
 *
 * <p>The session id, the baseline assistant message id, the pending form id and
 * the busy flag all live in the {@link HttpSession}, so a reconnect in the same
 * browser continues the same agent conversation.</p>
 */
@Slf4j
@Service
public class OpenCodeService {

    static final String ALREADY_RUNNING = "A search is already running in another tab.";
    static final String NO_QUESTION = "There is no question to answer.";

    static final String ATTR_SESSION_ID = "conv.sessionId";
    static final String ATTR_BASELINE = "conv.baselineMessageId";
    static final String ATTR_FORM_ID = "conv.pendingFormId";
    static final String ATTR_LAST_USED = "conv.lastUsed";
    static final String ATTR_BUSY = "conv.busy";

    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(30);

    private final OpenCodeAgentClient agent;

    public OpenCodeService(OpenCodeAgentClient agent) {
        this.agent = agent;
    }

    /** Sends the user's text verbatim and waits for an answer or a question. */
    public Outcome search(HttpSession http, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (isBusy(http)) {
            throw new UserMessageException(ALREADY_RUNNING);
        }
        dismissPendingQuestion(http);

        http.setAttribute(ATTR_BUSY, Boolean.TRUE);
        try {
            log.info("Sending prompt ({} chars): {}", query.length(), query);
            return runTurn(http, true, sessionId -> agent.prompt(sessionId, query));
        } finally {
            http.setAttribute(ATTR_BUSY, Boolean.FALSE);
        }
    }

    /** The form the agent is waiting on, or {@code null}. */
    public Form pendingForm(HttpSession http) {
        String formId = pendingFormId(http);
        String sessionId = (String) http.getAttribute(ATTR_SESSION_ID);
        if (formId == null || sessionId == null) {
            return null;
        }
        return agent.form(sessionId, formId);
    }

    private void dismissPendingQuestion(HttpSession http) {
        String formId = pendingFormId(http);
        if (formId == null) {
            return;
        }
        String sessionId = (String) http.getAttribute(ATTR_SESSION_ID);
        if (sessionId != null) {
            log.info("Dismissing the pending question formId='{}' for a new search", formId);
            agent.cancelForm(sessionId, formId);
        }
        http.removeAttribute(ATTR_FORM_ID);
    }

    /** Replies to the agent's pending form and waits for the next answer or question. */
    public Outcome answer(HttpSession http, Map<String, Object> values) {
        if (isBusy(http)) {
            throw new UserMessageException(NO_QUESTION);
        }
        String formId = pendingFormId(http);
        if (formId == null) {
            throw new UserMessageException(NO_QUESTION);
        }

        http.setAttribute(ATTR_BUSY, Boolean.TRUE);
        try {
            log.info("Sending form reply formId='{}'", formId);
            return runTurn(http, false, sessionId -> agent.reply(sessionId, formId, values));
        } finally {
            http.setAttribute(ATTR_BUSY, Boolean.FALSE);
        }
    }

    public static boolean isBusy(HttpSession http) {
        return Boolean.TRUE.equals(http.getAttribute(ATTR_BUSY));
    }

    public static String pendingFormId(HttpSession http) {
        return (String) http.getAttribute(ATTR_FORM_ID);
    }

    private Outcome runTurn(HttpSession http, boolean newTurn, Turn turn) {
        boolean retried = false;

        while (true) {
            String sessionId = ensureSession(http);
            try {
                String baseline = newTurn
                        ? agent.latestAssistantMessageId(sessionId)
                        : (String) http.getAttribute(ATTR_BASELINE);
                http.setAttribute(ATTR_BASELINE, baseline);

                turn.execute(sessionId);
                http.setAttribute(ATTR_LAST_USED, System.currentTimeMillis());

                Outcome outcome = agent.await(sessionId, baseline);
                http.setAttribute(ATTR_LAST_USED, System.currentTimeMillis());
                http.setAttribute(ATTR_FORM_ID,
                        outcome instanceof Outcome.Question question ? question.form().id() : null);

                log.info("OpenCode turn finished sessionId='{}' outcome={}",
                        sessionId, outcome.getClass().getSimpleName());
                return outcome;
            } catch (OpenCodeAgentClient.SessionNotFoundException e) {
                if (retried) {
                    throw e;
                }
                retried = true;
                log.warn("OpenCode session '{}' disappeared — recreating it and retrying once", sessionId);
                clearConversation(http);
            }
        }
    }

    private String ensureSession(HttpSession http) {
        String sessionId = (String) http.getAttribute(ATTR_SESSION_ID);
        Long lastUsed = (Long) http.getAttribute(ATTR_LAST_USED);
        boolean stale = lastUsed == null || System.currentTimeMillis() - lastUsed > SESSION_TTL_MS;

        if (sessionId == null || stale) {
            if (sessionId != null) {
                log.info("OpenCode session '{}' expired — starting a new one", sessionId);
                agent.deleteSession(sessionId);
                clearConversation(http);
            }
            sessionId = agent.createSession();
            http.setAttribute(ATTR_SESSION_ID, sessionId);
            http.setAttribute(ATTR_BASELINE, null);
            http.setAttribute(ATTR_FORM_ID, null);
        }

        http.setAttribute(ATTR_LAST_USED, System.currentTimeMillis());
        return sessionId;
    }

    private void clearConversation(HttpSession http) {
        http.removeAttribute(ATTR_SESSION_ID);
        http.removeAttribute(ATTR_BASELINE);
        http.removeAttribute(ATTR_FORM_ID);
        http.removeAttribute(ATTR_LAST_USED);
    }

    @FunctionalInterface
    private interface Turn {
        void execute(String sessionId);
    }
}
