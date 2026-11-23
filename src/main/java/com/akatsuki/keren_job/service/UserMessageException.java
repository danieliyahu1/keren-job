package com.akatsuki.keren_job.service;

/**
 * A turn that stopped for a reason the user can act on themselves, such as a
 * search already running in another tab. The message is shown verbatim instead
 * of being reported as an unexpected failure.
 */
public class UserMessageException extends RuntimeException {

    public UserMessageException(String message) {
        super(message);
    }
}
