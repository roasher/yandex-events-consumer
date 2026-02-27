package com.example.telegrambot.exception;

/**
 * Thrown when the Yandex Events API returns 429 TOO_MANY_REQUESTS (rate limited).
 * Callers should wait and retry.
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
