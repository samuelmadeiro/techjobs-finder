package com.techjobs.finder.exception;

import java.time.Duration;

/** Cliente passou do teto de requisições da rota. Vira HTTP 429. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Muitos envios em pouco tempo. Tente novamente em %d segundo(s)."
                .formatted(Math.max(1, retryAfter.toSeconds())));
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
