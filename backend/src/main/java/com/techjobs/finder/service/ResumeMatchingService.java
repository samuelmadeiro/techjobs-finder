package com.techjobs.finder.service;

import com.techjobs.finder.config.ResumeProperties;
import com.techjobs.finder.dto.recommendation.CompatibilityResult;
import com.techjobs.finder.dto.recommendation.CompatibilityResult.Reason;
import com.techjobs.finder.dto.recommendation.CompatibilityResult.Recommendation;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Resume;
import com.techjobs.finder.entity.ResumeSkill;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Compatibilidade entre um currículo e uma vaga.
 *
 * <p>O algoritmo é intencionalmente simples e auditável: cada critério vale um peso
 * configurável ({@code techjobs.resume.weights}), o critério que não pode ser avaliado
 * tem o peso redistribuído entre os demais, e cada ponto ganho ou perdido vira uma linha
 * de explicação. Nada de caixa-preta — o usuário precisa entender por que uma vaga
 * apareceu antes da outra.
 *
 * <p>Pesos padrão: habilidades 50, experiência 20, modalidade 10, localização 10,
 * tecnologias relacionadas 10.
 */
@Service
public class ResumeMatchingService {

    /** Uma tecnologia relacionada vale menos que a exata, mas não vale zero. */
    private static final double RELATED_CREDIT = 0.6;

    /** Tecnologias que se substituem parcialmente na prática. */
    private static final Map<String, Set<String>> RELATED = Map.ofEntries(
            Map.entry("spring-boot", Set.of("spring", "quarkus", "hibernate", "java")),
            Map.entry("spring", Set.of("spring-boot", "hibernate", "java")),
            Map.entry("quarkus", Set.of("spring-boot", "java")),
            Map.entry("hibernate", Set.of("spring-boot", "spring", "sql")),
            Map.entry("react", Set.of("nextjs", "react-native", "javascript", "typescript")),
            Map.entry("nextjs", Set.of("react", "javascript", "typescript")),
            Map.entry("angular", Set.of("typescript", "javascript")),
            Map.entry("vuejs", Set.of("javascript", "typescript", "nextjs")),
            Map.entry("nodejs", Set.of("javascript", "typescript", "express")),
            Map.entry("express", Set.of("nodejs", "javascript")),
            Map.entry("javascript", Set.of("typescript", "nodejs")),
            Map.entry("typescript", Set.of("javascript", "nodejs")),
            Map.entry("postgresql", Set.of("mysql", "sql", "oracle", "sqlserver")),
            Map.entry("mysql", Set.of("postgresql", "sql")),
            Map.entry("sql", Set.of("postgresql", "mysql", "oracle", "sqlserver")),
            Map.entry("oracle", Set.of("sql", "postgresql")),
            Map.entry("sqlserver", Set.of("sql", "postgresql")),
            Map.entry("mongodb", Set.of("redis", "elasticsearch")),
            Map.entry("kubernetes", Set.of("docker", "terraform")),
            Map.entry("docker", Set.of("kubernetes", "linux")),
            Map.entry("terraform", Set.of("aws", "kubernetes")),
            Map.entry("aws", Set.of("azure", "gcp", "terraform")),
            Map.entry("azure", Set.of("aws", "gcp")),
            Map.entry("gcp", Set.of("aws", "azure")),
            Map.entry("java", Set.of("kotlin", "scala", "spring-boot")),
            Map.entry("kotlin", Set.of("java")),
            Map.entry("scala", Set.of("java")),
            Map.entry("csharp", Set.of("dotnet")),
            Map.entry("dotnet", Set.of("csharp")),
            Map.entry("python", Set.of("django", "flask", "fastapi")),
            Map.entry("django", Set.of("python", "flask")),
            Map.entry("flask", Set.of("python", "fastapi")),
            Map.entry("fastapi", Set.of("python", "flask")));

    private final ResumeProperties properties;

    public ResumeMatchingService(ResumeProperties properties) {
        this.properties = properties;
    }

    /**
     * Perfil do currículo já achatado para comparação. Calcular isso uma vez e reusar em
     * todas as vagas da página evita repetir o mesmo trabalho por linha.
     */
    public record ResumeProfile(
            Set<String> skillSlugs,
            Map<String, String> displayNames,
            ExperienceLevel level,
            Integer years,
            WorkModel preferredWorkModel,
            String normalizedLocation) {

        public static ResumeProfile of(Resume resume) {
            Set<String> slugs = new LinkedHashSet<>();
            Map<String, String> names = new LinkedHashMap<>();
            for (ResumeSkill skill : resume.getSkills()) {
                Technology technology = skill.getTechnology();
                if (technology != null) {
                    slugs.add(technology.getSlug());
                    names.put(technology.getSlug(), technology.getName());
                }
            }
            return new ResumeProfile(
                    slugs,
                    names,
                    resume.getExperienceLevel(),
                    resume.getExperienceYears(),
                    resume.getPreferredWorkModel(),
                    Text.normalize(resume.getLocation()));
        }
    }

    public CompatibilityResult match(Resume resume, Job job) {
        return match(ResumeProfile.of(resume), job);
    }

    public CompatibilityResult match(ResumeProfile profile, Job job) {
        var weights = properties.getWeights();
        List<Reason> reasons = new ArrayList<>();

        double earned = 0;
        double available = 0;

        // --- habilidades exigidas pela vaga ------------------------------------
        Map<String, String> jobTech = job.getTechnologies().stream()
                .collect(Collectors.toMap(Technology::getSlug, Technology::getName, (a, b) -> a,
                        LinkedHashMap::new));

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> relatedHits = new ArrayList<>();

        for (Map.Entry<String, String> entry : jobTech.entrySet()) {
            if (profile.skillSlugs().contains(entry.getKey())) {
                matched.add(entry.getValue());
            } else if (hasRelated(profile.skillSlugs(), entry.getKey())) {
                relatedHits.add(entry.getValue());
                missing.add(entry.getValue());
            } else {
                missing.add(entry.getValue());
            }
        }

        if (!jobTech.isEmpty()) {
            available += weights.getSkills();
            earned += weights.getSkills() * ((double) matched.size() / jobTech.size());

            // O peso de "tecnologias relacionadas" só está em disputa quando sobra algo
            // não coberto exatamente. Sem lacuna não há o que compensar, e o peso sai da
            // conta — caso contrário um currículo com 100% de acerto exato ficaria em 90.
            int gaps = jobTech.size() - matched.size();
            if (gaps > 0) {
                available += weights.getRelatedTechnologies();
                earned += weights.getRelatedTechnologies() * RELATED_CREDIT
                        * ((double) relatedHits.size() / gaps);
            }

            matched.forEach(name -> reasons.add(Reason.good("skill", name)));
            relatedHits.forEach(name -> reasons.add(Reason.gap("related",
                    "Você não cita " + name + ", mas tem experiência em tecnologia próxima.")));
            missing.stream()
                    .filter(name -> !relatedHits.contains(name))
                    .forEach(name -> reasons.add(Reason.gap("skill",
                            "A vaga pede " + name + " e o currículo não menciona.")));
        }

        // --- nível de experiência ----------------------------------------------
        boolean experienceMatch = false;
        if (job.getExperienceLevel() != ExperienceLevel.UNKNOWN
                && profile.level() != ExperienceLevel.UNKNOWN) {
            available += weights.getExperience();
            double ratio = levelRatio(profile.level(), job.getExperienceLevel());
            earned += weights.getExperience() * ratio;
            experienceMatch = ratio >= 1;

            if (experienceMatch) {
                reasons.add(Reason.good("level",
                        "Nível " + label(job.getExperienceLevel()) + " compatível."));
            } else if (ratio > 0) {
                reasons.add(Reason.gap("level", "A vaga é de nível " + label(job.getExperienceLevel())
                        + " e seu perfil é " + label(profile.level()) + "."));
            } else {
                reasons.add(Reason.gap("level", "A vaga pede nível " + label(job.getExperienceLevel())
                        + ", distante do seu perfil " + label(profile.level()) + "."));
            }
        }

        // --- anos de experiência exigidos --------------------------------------
        if (job.getExperienceYears() != null && profile.years() != null) {
            if (profile.years() >= job.getExperienceYears()) {
                reasons.add(Reason.good("years",
                        "Você tem os %d ano(s) de experiência pedidos.".formatted(job.getExperienceYears())));
            } else {
                reasons.add(Reason.gap("years", "A vaga pede %d ano(s) de experiência e o currículo indica %d."
                        .formatted(job.getExperienceYears(), profile.years())));
            }
        }

        // --- modalidade ---------------------------------------------------------
        boolean workModelMatch = false;
        if (profile.preferredWorkModel() != null && job.getWorkModel() != WorkModel.UNKNOWN) {
            available += weights.getWorkModel();
            double ratio = workModelRatio(profile.preferredWorkModel(), job.getWorkModel());
            earned += weights.getWorkModel() * ratio;
            workModelMatch = ratio >= 1;
            if (workModelMatch) {
                reasons.add(Reason.good("workModel", "Modalidade " + label(job.getWorkModel()) + "."));
            } else {
                reasons.add(Reason.gap("workModel", "Você indica preferência por "
                        + label(profile.preferredWorkModel()) + " e a vaga é " + label(job.getWorkModel()) + "."));
            }
        }

        // --- localização --------------------------------------------------------
        boolean locationMatch = false;
        if (profile.normalizedLocation() != null) {
            available += weights.getLocation();
            double ratio = locationRatio(profile.normalizedLocation(), job);
            earned += weights.getLocation() * ratio;
            locationMatch = ratio >= 1;
            if (job.getWorkModel() == WorkModel.REMOTE) {
                reasons.add(Reason.good("location", "Vaga remota: a localização não limita."));
            } else if (locationMatch) {
                reasons.add(Reason.good("location", "Mesma localização do seu currículo."));
            } else {
                reasons.add(Reason.gap("location", "A vaga é em " + nz(job.getLocation())
                        + ", diferente da sua localização."));
            }
        }

        // Nenhum critério avaliável (vaga sem tecnologias, sem nível e sem local, ou
        // currículo sem dados): 50 é o "não sei", não um endosso.
        int score = available == 0 ? 50 : (int) Math.round((earned / available) * 100);
        score = Math.max(0, Math.min(100, score));

        List<String> extras = profile.skillSlugs().stream()
                .filter(slug -> !jobTech.containsKey(slug))
                .map(slug -> profile.displayNames().getOrDefault(slug, slug))
                .toList();

        return new CompatibilityResult(
                job.getId(),
                score,
                List.copyOf(matched),
                List.copyOf(missing),
                extras,
                experienceMatch,
                workModelMatch,
                locationMatch,
                Recommendation.fromScore(score),
                List.copyOf(reasons));
    }

    private boolean hasRelated(Set<String> skills, String wanted) {
        return RELATED.getOrDefault(wanted, Set.of()).stream().anyMatch(skills::contains);
    }

    /**
     * Estar acima do nível pedido não é problema; estar um degrau abaixo ainda vale
     * metade, porque muita vaga aceita quem está quase lá.
     */
    private double levelRatio(ExperienceLevel candidate, ExperienceLevel required) {
        if (candidate == required) {
            return 1;
        }
        int distance = candidate.rank() - required.rank();
        if (distance > 0) {
            return distance == 1 ? 1 : 0.8;
        }
        return distance == -1 ? 0.5 : 0;
    }

    private double workModelRatio(WorkModel preferred, WorkModel offered) {
        if (preferred == offered) {
            return 1;
        }
        boolean remoteHybrid = (preferred == WorkModel.REMOTE && offered == WorkModel.HYBRID)
                || (preferred == WorkModel.HYBRID && offered == WorkModel.REMOTE);
        return remoteHybrid ? 0.5 : 0;
    }

    /** Vaga remota atende qualquer lugar; presencial precisa bater cidade ou país. */
    private double locationRatio(String candidateLocation, Job job) {
        if (job.getWorkModel() == WorkModel.REMOTE) {
            return 1;
        }
        String haystack = Text.normalize(nz(job.getLocation()) + " " + nz(job.getCountry()));
        if (haystack == null || haystack.isBlank()) {
            return 0.5;
        }
        return haystack.contains(candidateLocation) || candidateLocation.contains(haystack) ? 1 : 0;
    }

    private String label(ExperienceLevel level) {
        return switch (level) {
            case INTERNSHIP -> "Estágio";
            case TRAINEE -> "Trainee";
            case JUNIOR -> "Júnior";
            case MID -> "Pleno";
            case SENIOR -> "Sênior";
            case UNKNOWN -> "não informado";
        };
    }

    private String label(WorkModel model) {
        return switch (model) {
            case REMOTE -> "Remoto";
            case HYBRID -> "Híbrido";
            case ONSITE -> "Presencial";
            case UNKNOWN -> "não informada";
        };
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
