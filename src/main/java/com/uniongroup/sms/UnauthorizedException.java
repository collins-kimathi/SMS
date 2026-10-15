package com.uniongroup.sms;

// Used to stop protected requests before they reach feature handlers.
final class UnauthorizedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnauthorizedException(String message) {
        super(message);
    }
}
