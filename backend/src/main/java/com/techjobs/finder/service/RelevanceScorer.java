package com.techjobs.finder.service;

import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.util.Text;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pontuação de relevância de 0 a 100 de uma vaga em relação ao filtro pedido.
 *
 * <p>Pesos: tecnologias 50, nível 25, modalidade 15, texto/localização 10.
 * Critério não informado pelo usuário não penaliza — seu peso é redistribuído entre
 * os critérios efetivamente pedidos, de forma que "Java + Júnior + Remoto" exato = 100.
 * Ao final é somado um bônus de até 3 pontos por vaga recém-publicada, que desempata
 * sem alterar a ordem entre faixas diferentes de aderência.
 */
@Component
public class RelevanceScorer {

    private static final double WEIGHT_TECHNOLOGY = 50;
    private static final double WEIGHT_LEVEL = 25;
    private static final double WEIGHT_WORK_MODEL = 15;
    private static final double WEIGHT_TEXT = 10;
    private static final double RECENCY_BONUS = 3;

    /** Tecnologias que contam como acerto parcial uma da outra (crédito de 60%). */
    private static final Map<String, Set<String>> RELATED = Map.ofEntries(
            Map.entry("spring-boot", Set.of("spring", "quarkus", "hibernate")),
            Map.entry("spring", Set.of("spring-boot", "hibernate")),
            Map.entry("react", Set.of("nextjs", "react-native", "javascript", "typescript")),
            Map.entry("nextjs", Set.of("react", "javascript", "typescript")),
            Map.entry("angular", Set.of("typescript", "javascript")),
            Map.entry("vuejs", Set.of("javascript", "typescript", "nextjs")),
            Map.entry("nodejs", Set.of("javascript", "typescript", "express")),
            Map.entry("javascript", Set.of("typescript", "nodejs")),
            Map.entry("typescript", Set.of("javascript", "nodejs")),
            Map.entry("postgresql", Set.of("mysql", "sql", "oracle", "sqlserver")),
            Map.entry("mysql", Set.of("postgresql", "sql", "mariadb")),
            Map.entry("kubernetes", Set.of("docker", "terraform")),
            Map.entry("docker", Set.of("kubernetes")),
            Map.entry("aws", Set.of("azure", "gcp")),
            Map.entry("azure", Set.of("aws", "gcp")),
            Map.entry("gcp", Set.of("aws", "azure")),
            Map.entry("java", Set.of("kotlin", "scala")),
            Map.entry("kotlin", Set.of("java")),
            Map.entry("csharp", Set.of("dotnet")),
            Map.entry("dotnet", Set.of("csharp")));

    private static final double PARTIAL_CREDIT = 0.6;

    public int score(Job job, JobSearchFilter filter) {
        double earned = 0;
        double available = 0;

        if (filter.hasTechnologyCriteria()) {
            available += WEIGHT_TECHNOLOGY;
            earned += WEIGHT_TECHNOLOGY * technologyRatio(job, filter);
        }
        if (filter.level() != null) {
            available += WEIGHT_LEVEL;
            earned += WEIGHT_LEVEL * levelRatio(job.getExperienceLevel(), filter.level());
        }
        if (filter.workModel() != null) {
            available += WEIGHT_WORK_MODEL;
            earned += WEIGHT_WORK_MODEL * workModelRatio(job.getWorkModel(), filter.workModel());
        }
        if (filter.keyword() != null || filter.location() != null) {
            available += WEIGHT_TEXT;
            earned += WEIGHT_TEXT * textRatio(job, filter);
        }

        // Filtro vazio: todas as vagas são igualmente relevantes; ordena só por recência.
        double base = available == 0 ? 70 : (earned / available) * 100;
        return (int) Math.round(Math.min(100, base + recencyBonus(job)));
    }

    private double technologyRatio(Job job, JobSearchFilter filter) {
        Set<String> jobSlugs = job.getTechnologies().stream()
                .map(Technology::getSlug)
                .collect(Collectors.toSet());
        var wanted = filter.allTechnologySlugs();
        double sum = 0;
        for (String slug : wanted) {
            if (jobSlugs.contains(slug)) {
                sum += 1;
            } else if (RELATED.getOrDefault(slug, Set.of()).stream().anyMatch(jobSlugs::contains)) {
                sum += PARTIAL_CREDIT;
            }
        }
        return wanted.isEmpty() ? 0 : sum / wanted.size();
    }

    /** Nível adjacente vale metade: pedir Júnior e receber Pleno ainda é útil. */
    private double levelRatio(ExperienceLevel actual, ExperienceLevel wanted) {
        if (actual == wanted) {
            return 1;
        }
        if (actual == ExperienceLevel.UNKNOWN || wanted == ExperienceLevel.UNKNOWN) {
            return 0.3;
        }
        int distance = Math.abs(actual.rank() - wanted.rank());
        return distance == 1 ? 0.5 : 0;
    }

    private double workModelRatio(WorkModel actual, WorkModel wanted) {
        if (actual == wanted) {
            return 1;
        }
        if (actual == WorkModel.UNKNOWN) {
            return 0.3;
        }
        // Híbrido atende parcialmente quem quer remoto e vice-versa.
        boolean remoteHybrid = (actual == WorkModel.HYBRID && wanted == WorkModel.REMOTE)
                || (actual == WorkModel.REMOTE && wanted == WorkModel.HYBRID);
        return remoteHybrid ? 0.5 : 0;
    }

    private double textRatio(Job job, JobSearchFilter filter) {
        double parts = 0;
        double matched = 0;

        if (filter.keyword() != null) {
            parts++;
            String haystack = Text.normalize(job.getTitle() + " " + nz(job.getSummary()));
            String needle = Text.normalize(filter.keyword());
            if (haystack != null && needle != null && haystack.contains(needle)) {
                matched++;
            } else {
                matched += Text.tokenSimilarity(job.getTitle(), filter.keyword()) > 0 ? 0.5 : 0;
            }
        }
        if (filter.location() != null) {
            parts++;
            String haystack = Text.normalize(nz(job.getLocation()) + " " + nz(job.getCountry()));
            String needle = Text.normalize(filter.location());
            if (haystack != null && needle != null && haystack.contains(needle)) {
                matched++;
            } else if (job.getWorkModel() == WorkModel.REMOTE) {
                // Vaga remota atende qualquer cidade, ainda que não cite o local pedido.
                matched += 0.7;
            }
        }
        return parts == 0 ? 0 : matched / parts;
    }

    private double recencyBonus(Job job) {
        Instant reference = job.getPublishedAt() != null ? job.getPublishedAt() : job.getFirstSeenAt();
        if (reference == null) {
            return 0;
        }
        long days = Duration.between(reference, Instant.now()).toDays();
        if (days <= 2) {
            return RECENCY_BONUS;
        }
        if (days <= 7) {
            return RECENCY_BONUS / 2;
        }
        return 0;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
