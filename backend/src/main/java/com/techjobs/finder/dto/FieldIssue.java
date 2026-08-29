package com.techjobs.finder.dto;

/**
 * Problema em um campo específico da requisição. Vai no {@code data} do envelope de erro,
 * para que a validação continue detalhada sem inventar um segundo formato de resposta.
 */
public record FieldIssue(String field, String message) {
}
