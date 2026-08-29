package com.techjobs.finder.mapper;

import com.techjobs.finder.dto.CatalogDtos;
import com.techjobs.finder.dto.job.CompanySummary;
import com.techjobs.finder.dto.job.JobDetailsResponse;
import com.techjobs.finder.dto.job.JobSourceSummary;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.dto.job.SalaryResponse;
import com.techjobs.finder.entity.Company;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.JobRequirement;
import com.techjobs.finder.entity.JobSource;
import com.techjobs.finder.entity.RequirementKind;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.TechnologyKind;
import java.util.List;
import org.springframework.stereotype.Component;

/** Conversão entidade -> DTO. Entidades JPA nunca cruzam a fronteira da API. */
@Component
public class JobMapper {

    /** Projeção do card: sem descrição completa e sem requisitos. */
    public JobSummaryResponse toSummary(Job job) {
        return new JobSummaryResponse(
                job.getId(),
                job.getTitle(),
                companyBrief(job.getCompany()),
                job.getLocation(),
                job.getWorkModel(),
                job.getExperienceLevel(),
                job.getExperienceYears(),
                languagesOf(job),
                technologiesOf(job),
                shortDescriptionOf(job),
                salaryOf(job),
                job.getPublishedAt(),
                sourceOf(job),
                job.getUrl(),
                null,
                null);
    }

    /** Projeção da tela de detalhes: tudo, incluindo o texto integral do anúncio. */
    public JobDetailsResponse toDetails(Job job) {
        return new JobDetailsResponse(
                job.getId(),
                job.getTitle(),
                companyFull(job.getCompany()),
                job.getLocation(),
                job.getCountry(),
                job.getWorkModel(),
                job.getExperienceLevel(),
                job.getExperienceYears(),
                shortDescriptionOf(job),
                job.getDescription() != null ? job.getDescription() : job.getSummary(),
                requirementsOf(job, RequirementKind.REQUIRED),
                requirementsOf(job, RequirementKind.NICE_TO_HAVE),
                languagesOf(job),
                technologiesOf(job),
                job.getBenefits(),
                salaryOf(job),
                job.getPublishedAt(),
                job.getUpdatedAt(),
                job.getExpirationDate(),
                job.isActive(),
                sourceOf(job),
                job.getUrl(),
                null);
    }

    public CatalogDtos.SourceResponse toResponse(JobSource source) {
        return new CatalogDtos.SourceResponse(
                source.getCode(),
                source.getName(),
                source.getBaseUrl(),
                source.isEnabled(),
                source.getLastRunAt(),
                source.getLastStatus(),
                source.getLastError());
    }

    private List<String> languagesOf(Job job) {
        return job.getTechnologies().stream()
                .filter(t -> t.getKind() == TechnologyKind.LANGUAGE)
                .map(Technology::getName)
                .sorted()
                .toList();
    }

    private List<String> technologiesOf(Job job) {
        return job.getTechnologies().stream()
                .filter(t -> t.getKind() != TechnologyKind.LANGUAGE)
                .map(Technology::getName)
                .sorted()
                .toList();
    }

    private List<String> requirementsOf(Job job, RequirementKind kind) {
        return job.getRequirements().stream()
                .filter(r -> r.getKind() == kind)
                .map(JobRequirement::getText)
                .toList();
    }

    /** Vagas antigas não têm {@code shortDescription}; o resumo cobre esse caso. */
    private String shortDescriptionOf(Job job) {
        if (job.getShortDescription() != null && !job.getShortDescription().isBlank()) {
            return job.getShortDescription();
        }
        String summary = job.getSummary();
        if (summary == null) {
            return null;
        }
        return summary.length() <= 400 ? summary : summary.substring(0, 397) + "...";
    }

    private SalaryResponse salaryOf(Job job) {
        SalaryResponse salary = new SalaryResponse(job.getSalaryMin(), job.getSalaryMax(),
                job.getSalaryCurrency(), job.getSalaryPeriod(), job.getSalaryRaw());
        return salary.isEmpty() ? null : salary;
    }

    private JobSourceSummary sourceOf(Job job) {
        JobSource source = job.getSource();
        if (source == null) {
            return null;
        }
        return new JobSourceSummary(source.getCode(), source.getName(), source.getBaseUrl());
    }

    private CompanySummary companyBrief(Company company) {
        if (company == null) {
            return null;
        }
        return CompanySummary.brief(company.getId(), company.getName(), company.getLogoUrl());
    }

    private CompanySummary companyFull(Company company) {
        if (company == null) {
            return null;
        }
        return new CompanySummary(company.getId(), company.getName(), company.getWebsite(),
                company.getLogoUrl(), company.getDescription());
    }
}
