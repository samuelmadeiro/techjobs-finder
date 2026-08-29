package com.techjobs.finder.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parâmetros da sessão autenticada. */
@ConfigurationProperties(prefix = "techjobs.auth")
public class AuthProperties {

    /** Nome do cookie. Prefixo {@code __Host-} não é usado porque exigiria HTTPS sempre. */
    private String cookieName = "tjf_session";

    /**
     * Marca o cookie como {@code Secure}. Ligado por padrão; desligar só faz sentido em
     * desenvolvimento sobre HTTP, e o valor vem de variável de ambiente justamente para
     * que produção não dependa de alguém lembrar de religar.
     */
    private boolean cookieSecure = true;

    /** Prazo máximo da sessão, mesmo em uso contínuo. Depois disso, autenticar de novo. */
    private Duration absoluteTimeout = Duration.ofDays(30);

    /** Prazo sem nenhuma requisição. Sessão parada além disso deixa de valer. */
    private Duration idleTimeout = Duration.ofDays(7);

    /**
     * Só grava {@code last_seen_at} quando o registro já está mais velho que isto.
     * Sem essa folga, toda requisição autenticada viraria um UPDATE — uma escrita por
     * leitura, que é o modo mais fácil de transformar sessão em gargalo.
     */
    private Duration touchInterval = Duration.ofMinutes(5);

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getTouchInterval() {
        return touchInterval;
    }

    public void setTouchInterval(Duration touchInterval) {
        this.touchInterval = touchInterval;
    }
}
