package com.uniongroup.sms;

// Used to stop protected requests before they reach feature handlers.
final class UnauthorizedException extends RuntimeException {
    UnauthorizedException(String message) {
        super(message);
    }
}
