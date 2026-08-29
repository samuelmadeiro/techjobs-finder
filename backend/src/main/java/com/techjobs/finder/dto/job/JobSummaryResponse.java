package com.techjobs.finder.dto.job;

import com.techjobs.finder.dto.recommendation.CompatibilityResult;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import java.time.Instant;
import java.util.List;

/**
 * Vaga como aparece no card da listagem: só o que o card mostra.
 * A descrição completa fica em {@link JobDetailsResponse}, carregada ao abrir o detalhe.
 *
 * @param relevance     aderência ao filtro pesquisado (0-100)
 * @param compatibility aderência ao currículo enviado; nulo quando não há currículo
 */
public record JobSummaryResponse(
        Long id,
        String title,
        CompanySummary company,
        String location,
        WorkModel workModel,
        ExperienceLevel experienceLevel,
        Integer experienceYears,
        List<String> languages,
        List<String> technologies,
        String shortDescription,
        SalaryResponse salary,
        Instant publishedAt,
        JobSourceSummary source,
        String originalUrl,
        Integer relevance,
        CompatibilityResult compatibility) {

    public JobSummaryResponse withRelevance(int score) {
        return new JobSummaryResponse(id, title, company, location, workModel, experienceLevel,
                experienceYears, languages, technologies, shortDescription, salary, publishedAt,
                source, originalUrl, score, compatibility);
    }

    public JobSummaryResponse withCompatibility(CompatibilityResult result) {
        return new JobSummaryResponse(id, title, company, location, workModel, experienceLevel,
                experienceYears, languages, technologies, shortDescription, salary, publishedAt,
                source, originalUrl, relevance, result);
    }
}
