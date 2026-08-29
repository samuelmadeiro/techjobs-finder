package com.techjobs.finder.entity;

import java.util.Locale;

/** Periodicidade da faixa salarial anunciada. */
public enum SalaryPeriod {
    HOUR,
    MONTH,
    YEAR;

    public static SalaryPeriod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "hour", "hr", "hora", "hourly", "/h" -> HOUR;
            case "month", "mes", "mês", "monthly", "mensal" -> MONTH;
            case "year", "yr", "ano", "annual", "annually", "anual" -> YEAR;
            default -> null;
        };
    }
}
