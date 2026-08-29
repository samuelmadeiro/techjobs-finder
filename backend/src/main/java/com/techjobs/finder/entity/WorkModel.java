package com.techjobs.finder.entity;

import java.util.Locale;

/** Modalidade de trabalho da vaga. */
public enum WorkModel {
    REMOTE,
    HYBRID,
    ONSITE,
    UNKNOWN;

    /** Aceita sinônimos em português e inglês vindos da API ou das fontes. */
    public static WorkModel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "remote", "remoto", "home office", "home-office", "anywhere", "fully remote" -> REMOTE;
            case "hybrid", "hibrido", "híbrido", "semi presencial", "semipresencial" -> HYBRID;
            case "onsite", "on-site", "presencial", "office", "escritorio", "escritório" -> ONSITE;
            case "all", "todas", "any" -> null;
            default -> UNKNOWN;
        };
    }
}
