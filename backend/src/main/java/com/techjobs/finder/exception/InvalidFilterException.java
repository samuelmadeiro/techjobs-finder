package com.techjobs.finder.exception;

/** Filtro recebido com valor fora do domínio aceito. Vira HTTP 400. */
public class InvalidFilterException extends RuntimeException {

    private final String field;

    public InvalidFilterException(String field, String value, String hint) {
        super("Valor inválido para '%s': '%s'. %s".formatted(field, value, hint));
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
