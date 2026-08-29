package com.techjobs.finder.dto.job;

/** Empresa como aparece dentro de uma vaga. */
public record CompanySummary(
        Long id,
        String name,
        String website,
        String logoUrl,
        String description) {

    /** Versão enxuta usada no card da listagem. */
    public static CompanySummary brief(Long id, String name, String logoUrl) {
        return new CompanySummary(id, name, null, logoUrl, null);
    }
}
