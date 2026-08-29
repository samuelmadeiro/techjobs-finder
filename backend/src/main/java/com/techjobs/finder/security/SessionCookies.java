package com.techjobs.finder.security;

import com.techjobs.finder.config.AuthProperties;
import com.techjobs.finder.service.AuthenticationService.IssuedSession;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Escreve e apaga o cookie de sessão.
 *
 * <p>Cookie e não corpo da resposta porque o token dá acesso a currículo — dado pessoal
 * completo. Em {@code localStorage}, qualquer script injetado na página o lê e o envia
 * embora; com {@code HttpOnly} o JavaScript não enxerga o valor, então um XSS pode agir
 * dentro da sessão da vítima enquanto ela está na página, mas não sai com a credencial.
 *
 * <p>{@code SameSite=Lax} é o que substitui um token CSRF aqui: o navegador não manda o
 * cookie em POST vindo de outro site, e as rotas que mudam estado são POST e DELETE —
 * DELETE nem é possível a partir de um formulário HTML. GET não altera nada.
 */
public class SessionCookies {

    private final AuthProperties properties;

    public SessionCookies(AuthProperties properties) {
        this.properties = properties;
    }

    public void write(HttpServletResponse response, IssuedSession session) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(session.token(), session.maxAge()).toString());
    }

    /** Apaga o cookie no logout: mesmo nome, mesmo caminho, validade zero. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(properties.getCookieName(), value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                // Raiz e não /api: o cookie precisa acompanhar qualquer caminho servido
                // pelo mesmo host, inclusive se a API mudar de prefixo.
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
