package com.techjobs.finder.dto;

import java.time.Instant;
import java.util.List;

/**
 * Envelope único de todas as respostas da API.
 *
 * <p>O frontend checa {@code success} uma vez e lê {@code data} sem precisar conhecer
 * um formato diferente por endpoint. Em erro, {@code data} é nulo e {@code message}
 * traz o texto exibível ao usuário — nunca detalhe interno do servidor.
 *
 * <p>{@code errors} existe para o que a {@code message} não consegue dizer: qual campo
 * está errado. Antes essa lista viajava dentro de {@code data}, onde o cliente nunca a
 * procurava — em erro ele lê {@code message} e descarta {@code data} —, então a validação
 * detalhada existia e não chegava a ninguém. Campo próprio, com {@code non_null}
 * configurado no Jackson: some do JSON quando não há nada a relatar.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        List<FieldIssue> errors,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null, Instant.now());
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message, null, Instant.now());
    }

    /** Erro de validação: a mensagem resume, a lista diz exatamente onde corrigir. */
    public static <T> ApiResponse<T> fail(String message, List<FieldIssue> errors) {
        return new ApiResponse<>(false, null, message, errors, Instant.now());
    }
}
