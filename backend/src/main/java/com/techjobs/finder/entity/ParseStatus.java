package com.techjobs.finder.entity;

/** Resultado da extração de texto e análise do currículo. */
public enum ParseStatus {
    PENDING,
    /** Texto extraído e perfil montado. */
    PARSED,
    /** Arquivo lido, mas sem texto aproveitável (ex.: PDF só de imagem). */
    EMPTY,
    FAILED
}
