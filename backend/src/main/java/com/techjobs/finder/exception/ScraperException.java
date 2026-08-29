package com.techjobs.finder.exception;

/**
 * Falha na coleta de uma fonte. Nunca propaga para o cliente: é capturada pelo
 * orquestrador, registrada em log e reportada no {@code meta.failures} da resposta.
 */
public class ScraperException extends RuntimeException {

    private final String source;

    public ScraperException(String source, String message) {
        super(message);
        this.source = source;
    }

    public ScraperException(String source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
