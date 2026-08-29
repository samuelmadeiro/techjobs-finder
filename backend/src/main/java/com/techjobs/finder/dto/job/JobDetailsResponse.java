package com.techjobs.finder.dto.job;

import com.techjobs.finder.dto.recommendation.CompatibilityResult;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import java.time.Instant;
import java.util.List;

/**
 * Vaga completa, devolvida por {@code GET /api/jobs/{id}}.
 *
 * <p>{@code originalUrl} é o link da fonte sem nenhuma reescrita: o usuário precisa
 * chegar ao anúncio de verdade para se candidatar.
 */
public record JobDetailsResponse(
        Long id,
        String title,
        CompanySummary company,
        String location,
        String country,
        WorkModel workModel,
        ExperienceLevel experienceLevel,
        Integer experienceYears,
        String shortDescription,
        String description,
        List<String> requirements,
        List<String> niceToHave,
        List<String> languages,
        List<String> technologies,
        String benefits,
        SalaryResponse salary,
        Instant publishedAt,
        Instant updatedAt,
        Instant expirationDate,
        boolean active,
        JobSourceSummary source,
        String originalUrl,
        CompatibilityResult compatibility) {

    public JobDetailsResponse withCompatibility(CompatibilityResult result) {
        return new JobDetailsResponse(id, title, company, location, country, workModel,
                experienceLevel, experienceYears, shortDescription, description, requirements,
                niceToHave, languages, technologies, benefits, salary, publishedAt, updatedAt,
                expirationDate, active, source, originalUrl, result);
    }
}
