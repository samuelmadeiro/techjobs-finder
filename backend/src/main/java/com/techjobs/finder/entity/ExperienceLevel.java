package com.techjobs.finder.entity;

import java.util.Locale;

/** Nível de experiência exigido pela vaga. */
public enum ExperienceLevel {
    INTERNSHIP(0),
    TRAINEE(1),
    JUNIOR(2),
    MID(3),
    SENIOR(4),
    UNKNOWN(-1);

    private final int rank;

    ExperienceLevel(int rank) {
        this.rank = rank;
    }

    /** Ordem crescente de senioridade; UNKNOWN fica fora da escala. */
    public int rank() {
        return rank;
    }

    /** Aceita os rótulos em português usados no frontend e sinônimos em inglês. */
    public static ExperienceLevel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "internship", "intern", "estagio", "estágio" -> INTERNSHIP;
            case "trainee" -> TRAINEE;
            case "junior", "júnior", "jr", "entry_level", "entry-level", "entry level" -> JUNIOR;
            case "mid", "pleno", "mid_level", "mid-level", "middle", "intermediate" -> MID;
            case "senior", "sênior", "sr", "staff", "principal", "lead", "specialist" -> SENIOR;
            case "all", "todos", "any" -> null;
            default -> UNKNOWN;
        };
    }
}
