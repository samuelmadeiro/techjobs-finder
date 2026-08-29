package com.techjobs.finder.web;

import com.techjobs.finder.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica o limite de envios antes de o multipart ser lido inteiro.
 *
 * <p>Interceptor e não validação dentro do serviço: recusar cedo é o ponto do limite —
 * quem passou do teto não deve custar upload, extração de PDF nem transação.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;

    public RateLimitInterceptor(RateLimiter rateLimiter, ClientKeyResolver clientKeyResolver) {
        this.rateLimiter = rateLimiter;
        this.clientKeyResolver = clientKeyResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        Optional<Duration> wait = rateLimiter.consume(clientKeyResolver.resolve(request));
        if (wait.isEmpty()) {
            return true;
        }
        // A chave não vai para o log: ela contém o token do currículo.
        log.info("Limite de envios atingido em {}; nova tentativa em {}s",
                request.getRequestURI(), wait.get().toSeconds());
        throw new RateLimitExceededException(wait.get());
    }
}
