package com.uniongroup.sms;

// Used to reject protected requests before they reach feature handlers (HTTP 403 Forbidden).
final class SecurityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SecurityException(String message) {
        super(message);
    }
}
